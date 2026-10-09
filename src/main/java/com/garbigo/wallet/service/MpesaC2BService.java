package com.premisave.wallet.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.premisave.wallet.client.AuthServiceClient;
import com.premisave.wallet.config.MpesaConfig;
import com.premisave.wallet.dto.MpesaC2BCallbackRequest;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DepositStatus;
import com.premisave.wallet.exception.C2BUrlsAlreadyRegisteredException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DepositRepository;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaC2BService {

    private final MpesaConfig config;
    private final MpesaService mpesaService;   // reuse getAccessToken() + normalizePhone()
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final DepositRepository depositRepository;
    private final DepositTransactionRecorder depositTransactionRecorder;
    private final EmailService emailService;
    private final ExchangeRateService exchangeRateService;
    private final AuthServiceClient authServiceClient;
    private final UserNameResolver userNameResolver;

    /**
     * Timeouts bumped up from OkHttp's 10s default, since Safaricom's
     * sandbox can be slow — especially right after service startup.
     *
     * Deliberately does NOT pin the protocol list (no forced HTTP/1.1).
     * An earlier version of this client forced Protocol.HTTP_1_1 to work
     * around what looked like an HTTP/2 stream stall on registerurl — but
     * that pin has since correlated with connection failures (Permission
     * denied / unexpected end of stream) against this same sandbox host,
     * while MpesaTokenService's plain default-protocol OkHttpClient (no
     * pinning at all) has succeeded on every single call to this same host
     * throughout testing. Left as OkHttp's default protocol negotiation
     * (h2 + http/1.1) to match that proven-working client instead.
     */
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    // ─── URL Registration ─────────────────────────────────────────────────────

    /**
     * Registers validation + confirmation URLs with Safaricom.
     * Call this ONCE after deployment (or on every startup — Safaricom is idempotent).
     *
     * Uses mpesa.daraja.c2b.* directly (shortcode, response-type, validation-url,
     * confirmation-url) instead of deriving paths from the STK callback URL —
     * the C2B test shortcode is different from the STK Push test shortcode
     * in Daraja sandbox, so they must be configured independently.
     *
     * Retries up to 3 times on SocketTimeoutException — the sandbox has been
     * observed to time out on the first couple of attempts and then succeed
     * on an identical retry, so a transient stall shouldn't surface as a
     * hard failure to the caller. A real rejection from Safaricom (parsed
     * error/failure response) is NOT retried — it's thrown immediately.
     *
     * Safaricom uses TWO DIFFERENT response shapes here, and both are valid
     * JSON, so parsing alone doesn't tell you which one you got:
     *   - Rejection:  {"requestId": "...", "errorCode": "...", "errorMessage": "..."}
     *   - Acceptance: {"ResponseCode": "0", "ResponseDescription": "success", ...}
     * Blindly reading ResponseCode/ResponseDescription/CustomerMessage off an
     * error payload just returns empty strings for all three — which used to
     * get wrapped in ApiResponse.success(...) by the controller, masking a
     * real failure as a fake success. This now throws on either shape of
     * rejection so the failure surfaces honestly instead.
     *
     * ResponseCode success value is NOT consistently "0" for this specific
     * endpoint — sandbox has been observed returning "00000000" (all zeros)
     * instead of a bare "0" (unlike STK Push/B2B/B2C, which do use a bare
     * "0"). An exact-match check against "0" therefore misclassifies a real
     * Safaricom success as a failure. success is now determined by BOTH:
     *   1. ResponseCode being present and consisting entirely of '0' chars
     *      (covers "0", "00000000", or any other zero-padding Safaricom
     *      might use), OR
     *   2. ResponseDescription itself reading "success" (case-insensitive),
     *      as a fallback in case ResponseCode is ever blank/malformed but
     *      the description clearly indicates acceptance.
     */
    public Map<String, Object> registerUrls() {
        String token = mpesaService.getAccessToken();

        Map<String, Object> body = Map.of(
                "ShortCode",        config.getC2b().getShortcode(),
                "ResponseType",     config.getC2b().getResponseType(),
                "ConfirmationURL",  config.getC2b().getConfirmationUrl(),
                "ValidationURL",    config.getC2b().getValidationUrl()
        );

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("C2B URL registration failed: " + e.getMessage(), e);
        }

        RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(config.baseUrl() + "/mpesa/c2b/v2/registerurl")
                .addHeader("Authorization", "Bearer " + token)
                .post(rb)
                .build();

        final int maxAttempts = 3;
        SocketTimeoutException lastTimeout = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Response response = http.newCall(request).execute()) {
                String respBody = response.body().string();
                log.info("C2B URL registration response (attempt {}/{}): {}", attempt, maxAttempts, respBody);
                return parseRegisterUrlsResponse(respBody);
            } catch (SocketTimeoutException e) {
                lastTimeout = e;
                log.warn("C2B URL registration timed out (attempt {}/{}) — retrying...", attempt, maxAttempts);
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(1500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (RuntimeException e) {
                // Real rejection/parsing failure from Safaricom — don't retry, surface immediately.
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("C2B URL registration failed: " + e.getMessage(), e);
            }
        }

        throw new RuntimeException(
                "C2B URL registration failed: timeout after " + maxAttempts + " attempts", lastTimeout);
    }

    /**
     * Parses Safaricom's registerurl response body, distinguishing the
     * rejection shape from the acceptance shape, and applying the
     * all-zeros ResponseCode / "success" description success check
     * described above.
     *
     * "Already registered" (errorCode 500.003.1001, "Duplicate
     * notification info") is deliberately distinguished from every other
     * rejection and thrown as C2BUrlsAlreadyRegisteredException, not a
     * generic RuntimeException — this is a real, well-understood
     * Safaricom state (URLs already on file for this shortcode), not a
     * system fault, and previously fell through to
     * GlobalExceptionHandler's catch-all as a bare 500 with no indication
     * of what actually went wrong. Matches on the specific error code
     * first (most precise), falling back to a case-insensitive substring
     * check on the message in case Safaricom's exact code ever shifts
     * slightly across environments — same defensive-fallback pattern used
     * for Flutterwave's transfer-failure field-name uncertainty elsewhere
     * in this codebase.
     *
     * NOTE on CustomerMessage: Safaricom returns this field on several
     * Daraja APIs (most notably STK Push) as text meant to be shown to the
     * end customer on their phone/UI. Register URL has no end-customer in
     * the loop — it's a backend/admin action — so Safaricom always returns
     * it blank here. That's expected, not a parsing bug; we still pass it
     * through in the raw payload for completeness, but build our own
     * "message" below rather than relying on it.
     */
    private Map<String, Object> parseRegisterUrlsResponse(String respBody) throws Exception {
        JsonNode node = objectMapper.readTree(respBody);

        // ── Rejection shape: {requestId, errorCode, errorMessage} ──────
        String errorCode = node.path("errorCode").asString(null);
        if (errorCode != null && !errorCode.isBlank()) {
            String errorMessage = node.path("errorMessage").asString("Unknown C2B registration error");
            log.warn("C2B URL registration rejected by Safaricom: errorCode={} errorMessage={}",
                    errorCode, errorMessage);

            if ("500.003.1001".equals(errorCode)
                    || errorMessage.toLowerCase().contains("duplicate notification")) {
                throw new C2BUrlsAlreadyRegisteredException(
                        "C2B URLs are already registered for shortcode " + config.getC2b().getShortcode()
                                + " (Safaricom: " + errorMessage + "). Delete the existing registration first "
                                + "(see c2b_url_deletion.java) before retrying — Safaricom doesn't support "
                                + "overwriting an existing C2B URL registration in place.");
            }

            throw new RuntimeException(
                    "C2B URL registration failed (" + errorCode + "): " + errorMessage);
        }

        // ── Acceptance shape ─────────────────────────────────────────
        String responseCode = node.path("ResponseCode").asString("");
        String description  = node.path("ResponseDescription").asString("");

        boolean codeIndicatesSuccess = !responseCode.isBlank()
                && responseCode.chars().allMatch(c -> c == '0');
        boolean descriptionIndicatesSuccess = "success".equalsIgnoreCase(description.trim());

        boolean success = codeIndicatesSuccess || descriptionIndicatesSuccess;

        if (!success) {
            String desc = !description.isBlank() ? description : "Unknown failure";
            log.warn("C2B URL registration not accepted: responseCode={} description={} raw={}",
                    responseCode, desc, respBody);
            throw new RuntimeException(
                    "C2B URL registration was not accepted (ResponseCode=" + responseCode + "): " + desc);
        }

        log.info("C2B URL registration accepted: responseCode={} description={}", responseCode, description);

        String friendlyMessage = "C2B URLs have been registered successfully with Safaricom.";

        return Map.of(
                "success",             true,
                "message",             friendlyMessage,
                "ResponseCode",        responseCode,
                "ResponseDescription", description,
                "CustomerMessage",     node.path("CustomerMessage").asString()
        );
    }

    // ─── Simulate Transaction (sandbox testing only) ─────────────────────────

    /**
     * Calls Safaricom's C2B Simulate Transaction endpoint, which pretends a
     * customer paid our Pay Bill shortcode — Safaricom then calls OUR
     * registered validation URL, and (if accepted) OUR confirmation URL,
     * exactly like a real payment would. This is the standard way to
     * exercise the full C2B flow end-to-end without a real phone, since
     * Daraja's sandbox has no live MSISDNs.
     *
     * HARD-BLOCKED outside sandbox (config.getEnvironment() != "sandbox")
     * — Safaricom does not expose this endpoint in production at all, but
     * we still guard it here defensively so a misconfigured environment
     * can't silently attempt it.
     *
     * Prerequisites for this to actually complete the flow:
     *   1. registerUrls() has already been called successfully, so
     *      Safaricom has our validation/confirmation URLs on file.
     *   2. This service is reachable via a public HTTPS tunnel (e.g.
     *      ngrok) matching mpesa.daraja.c2b.validation-url/confirmation-url.
     *   3. billRefNumber matches an existing wallet's mpesaPhoneNumber —
     *      Safaricom will call our validation URL with it as
     *      BillRefNumber, same as processConfirmation/validateAccount
     *      expect elsewhere.
     *
     * @param amount        transaction amount, e.g. "100"
     * @param msisdn        Safaricom sandbox test MSISDN, e.g. "254705912645"
     * @param billRefNumber account reference — must match an existing wallet's
     *                      mpesaPhoneNumber to pass validation
     */
    public Map<String, Object> simulateC2BPayment(String amount, String msisdn, String billRefNumber) {
        if (!"sandbox".equalsIgnoreCase(config.getEnvironment())) {
            throw new IllegalStateException(
                    "C2B simulate is a sandbox-only testing tool and is disabled because "
                            + "mpesa.daraja.environment=" + config.getEnvironment());
        }

        if (billRefNumber == null || billRefNumber.isBlank()) {
            throw new IllegalArgumentException("billRefNumber is required");
        }

        String token = mpesaService.getAccessToken();

        Map<String, Object> body = Map.of(
                "ShortCode",     config.getC2b().getShortcode(),
                "CommandID",     "CustomerPayBillOnline",
                "Amount",        amount != null && !amount.isBlank() ? amount : "100",
                "Msisdn",        msisdn != null && !msisdn.isBlank() ? msisdn : "254705912645",
                "BillRefNumber", billRefNumber
        );

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("C2B simulate failed: " + e.getMessage(), e);
        }

        RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(config.baseUrl() + "/mpesa/c2b/v2/simulate")
                .addHeader("Authorization", "Bearer " + token)
                .post(rb)
                .build();

        try (Response response = http.newCall(request).execute()) {
            String respBody = response.body().string();
            log.info("C2B simulate response: {}", respBody);
            return parseSimulateResponse(respBody);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("C2B simulate failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> parseSimulateResponse(String respBody) throws Exception {
        JsonNode node = objectMapper.readTree(respBody);

        String errorCode = node.path("errorCode").asString(null);
        if (errorCode != null && !errorCode.isBlank()) {
            String errorMessage = node.path("errorMessage").asString("Unknown C2B simulate error");
            log.warn("C2B simulate rejected by Safaricom: errorCode={} errorMessage={}",
                    errorCode, errorMessage);
            throw new RuntimeException("C2B simulate failed (" + errorCode + "): " + errorMessage);
        }

        String responseDescription = node.path("ResponseDescription").asString("");
        log.info("C2B simulate accepted: {}", responseDescription);

        return Map.of(
                "success", true,
                "message", "Simulated payment accepted by Safaricom — check application logs for the "
                        + "validation/confirmation callback, then verify the wallet balance.",
                "ConversationID",           node.path("ConversationID").asString(""),
                "OriginatorConversationID", node.path("OriginatorConversationID").asString(""),
                "ResponseDescription",      responseDescription
        );
    }

    // ─── Validation ───────────────────────────────────────────────────────────

    /**
     * Dual-layer account validation for M-Pesa C2B (external validation enabled).
     *
     * The "account" a customer types into the Pay Bill Account Number field
     * is now the wallet's M-Pesa phone number (mpesaPhoneNumber) — not the
     * email used previously. Normalized the same way it's stored
     * (MpesaService.normalizePhone) before lookup.
     *
     * Layer 1 — Local wallet lookup: confirms a wallet is actually
     *            registered under this number, so we have somewhere to
     *            credit the funds. Checked FIRST now (order flipped from
     *            the email-keyed version) since there's no email to
     *            validate against auth-service until we've resolved the
     *            wallet.
     *
     * Layer 2 — Auth Service: confirms the wallet owner's account is
     *            active, verified, non-archived, using the email resolved
     *            from the wallet. Fail-safe: if auth service is
     *            unreachable, we REJECT (fail closed) — better to decline
     *            than accept an unknown/unverifiable account.
     *
     * Safaricom must receive a response within 8 seconds — both checks are
     * fast indexed lookups designed to stay well within that window.
     */
    public boolean validateAccount(String billRefNumber) {
        if (billRefNumber == null || billRefNumber.isBlank()) {
            log.warn("C2B validation: empty account reference received");
            return false;
        }

        String normalizedPhone = mpesaService.normalizePhone(billRefNumber.trim());

        // ── Layer 1: Confirm a wallet actually exists locally for this number ──
        Optional<Wallet> walletOpt = walletRepository.findByMpesaPhoneNumber(normalizedPhone);
        if (walletOpt.isEmpty()) {
            log.warn("C2B validation: no wallet registered for mpesaPhoneNumber={}", normalizedPhone);
            return false;
        }
        Wallet wallet = walletOpt.get();

        // ── Layer 2: Verify the wallet owner is active/verified in auth service ──
        try {
            Map<String, Object> result = authServiceClient.validateEmail(wallet.getAccountNumber());
            boolean valid = Boolean.TRUE.equals(result.get("valid"));

            if (!valid) {
                String reason = (String) result.getOrDefault("reason", "UNKNOWN");
                log.warn("C2B validation: auth service rejected email={} (mpesaPhoneNumber={}) reason={}",
                        wallet.getAccountNumber(), normalizedPhone, reason);
                return false;
            }

            log.debug("C2B validation: auth service confirmed mpesaPhoneNumber={} email={}",
                    normalizedPhone, wallet.getAccountNumber());
            return true;
        } catch (Exception e) {
            // Auth service is down — FAIL CLOSED (reject payment)
            log.error("C2B validation: auth service unreachable for mpesaPhoneNumber={} — rejecting (fail-safe). Error: {}",
                    normalizedPhone, e.getMessage());
            return false;
        }
    }

    // ─── Confirmation ─────────────────────────────────────────────────────────

    /**
     * Called after Safaricom confirms the payment on their side.
     * Idempotent — skips duplicate TransIDs already in the DB.
     * BillRefNumber is now the wallet's M-Pesa phone number, not email —
     * normalized before lookup, same as validateAccount above.
     *
     * C2B has no prior "pending" record the way STK push does — the
     * customer pays directly from their own M-Pesa menu, with no
     * initiation step on our side at all — so this confirmation callback
     * IS the first and only time this transaction is ever known about.
     * The Deposit record is therefore created directly in SUCCESS state
     * here, rather than updating an existing PENDING one.
     *
     * Previously this method only ever created a generic Transaction row
     * (never a Deposit), credited the wallet with the raw KES amount
     * with NO currency conversion at all, and never sent a confirmation
     * email — this method predated the Deposit-entity migration every
     * other M-Pesa/Flutterwave deposit path already went through, and
     * was simply never updated to match. Fixed here to use the same
     * Deposit + DepositTransactionRecorder + KES->USD conversion + email
     * pattern as MpesaDepositService.creditWalletFromStkCallback.
     */
    @Transactional
    public void processConfirmation(MpesaC2BCallbackRequest req) {
        String normalizedPhone = mpesaService.normalizePhone(req.getBillRefNumber().trim());
        String transId    = req.getTransID();
        BigDecimal kesAmount = new BigDecimal(req.getTransAmount());

        // Idempotency — skip if we've already processed this M-Pesa transaction
        if (transactionRepository.existsByProviderReference(transId)) {
            log.warn("C2B duplicate ignored: transId={}", transId);
            return;
        }

        Wallet wallet = walletRepository.findByMpesaPhoneNumber(normalizedPhone)
                .orElseThrow(() -> new WalletNotFoundException(
                        "C2B confirmation: no wallet for mpesaPhoneNumber=" + normalizedPhone));

        // The wallet is USD-denominated; M-Pesa C2B payments are always
        // KES. Converted here, BEFORE ever touching wallet.balance — same
        // principle as every other M-Pesa/Flutterwave deposit path in
        // this codebase. Previously this was added directly with no
        // conversion at all — a customer paying 1,000 KES would have
        // added 1,000 straight to a USD balance.
        BigDecimal rate = exchangeRateService.getRate("KES", "USD");
        BigDecimal usdAmount = kesAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);

        wallet.setBalance(wallet.getBalance().add(usdAmount));
        walletRepository.save(wallet);

        String senderName = buildSenderName(req);
        // Wallet owner's own name — resolved via the auth service, same
        // as the sender's name Safaricom already gave us, but for the
        // person on our own side of this transaction.
        String recipientName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());

        Deposit deposit = new Deposit();
        deposit.setUserId(wallet.getUserId());
        deposit.setWalletId(wallet.getId());
        deposit.setAmount(usdAmount);
        deposit.setCurrency(Currency.USD);
        deposit.setPriceAmount(kesAmount);
        deposit.setPriceCurrency("kes");
        deposit.setProvider("MPESA");
        deposit.setChannel("MPESA_C2B");
        deposit.setSource(req.getMSISDN());
        deposit.setSenderName(senderName);
        deposit.setRecipientName(recipientName);
        deposit.setStatus(DepositStatus.SUCCESS);
        deposit.setReference(transId);
        deposit.setProviderReference(transId);
        depositRepository.save(deposit);

        depositTransactionRecorder.record(wallet.getUserId(), wallet.getId(), usdAmount, deposit, transId);

        String exchangeRateInfo = "1 KES = " + rate.toPlainString() + " USD";
        emailService.sendDepositConfirmation(wallet.getAccountNumber(), usdAmount.toPlainString(),
                deposit.getCurrency().name(), deposit.getReference(), wallet.getBalance().toPlainString(),
                new EmailService.DepositDetails("M-Pesa", exchangeRateInfo, req.getMSISDN(),
                        senderName, transId, recipientName, wallet.getAccountNumber(), wallet.getId()));
        log.info("C2B deposit processed: accountNumber={} mpesaPhoneNumber={} kesAmount={} usdAmount={} rate={} " +
                        "transId={} sender={}",
                wallet.getAccountNumber(), normalizedPhone, kesAmount, usdAmount, rate, transId, senderName);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String buildSenderName(MpesaC2BCallbackRequest req) {
        StringBuilder name = new StringBuilder();
        if (req.getFirstName()  != null) name.append(req.getFirstName()).append(" ");
        if (req.getMiddleName() != null && !req.getMiddleName().isBlank())
            name.append(req.getMiddleName()).append(" ");
        if (req.getLastName()   != null) name.append(req.getLastName());
        String result = name.toString().trim();
        return result.isBlank() ? "Unknown" : result;
    }
}