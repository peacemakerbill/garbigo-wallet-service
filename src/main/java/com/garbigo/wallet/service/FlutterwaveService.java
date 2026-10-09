package com.premisave.wallet.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.premisave.wallet.config.FlutterwaveConfig;
import com.premisave.wallet.exception.FlutterwaveTransferException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Flutterwave v4 service.
 *
 * Sandbox base URL   : https://developersandbox-api.flutterwave.com
 * Production base URL: https://f4bexperience.flutterwave.com
 *
 * FlutterwaveConfig.baseUrl() must return the correct URL for the environment.
 *
 * OAuth2 token endpoint (both environments):
 *   https://idp.flutterwave.com/realms/flutterwave/protocol/openid-connect/token
 *
 * IDEMPOTENCY: every POST call below takes an explicit idempotencyKey
 * argument derived from the caller's own reference — NEVER a freshly
 * randomized UUID per call. Per Flutterwave's idempotency docs: "When a
 * subsequent request is made with the same idempotency key, we return the
 * original response associated with the first request that used that key."
 * A random-per-call key defeats this entirely and risks duplicate charges/
 * transfers on any client retry, load-balancer replay, or double-click.
 * X-Trace-Id is unrelated to idempotency (it's a debugging/tracing id) and
 * is still safely randomized per call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlutterwaveService {

    // Correct OAuth2 token URL from official v4 docs
    private static final String OAUTH_TOKEN_URL =
            "https://idp.flutterwave.com/realms/flutterwave/protocol/openid-connect/token";

    // Token lifetime is 600s (10 min). Refresh 60s before expiry.
    private static final long TOKEN_REFRESH_MARGIN_MILLIS = 60_000L;

    private final FlutterwaveConfig config;
    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final OkHttpClient http = new OkHttpClient();

    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile String cachedAccessToken;
    private volatile long tokenExpiresAtEpochMillis = 0L;

    // ─── OAuth2 token management ──────────────────────────────────────────────

    private String getAccessToken() {
        if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiresAtEpochMillis) {
            return cachedAccessToken;
        }

        tokenLock.lock();
        try {
            if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiresAtEpochMillis) {
                return cachedAccessToken;
            }

            FormBody form = new FormBody.Builder()
                    .add("client_id", config.getClientId())
                    .add("client_secret", config.getClientSecret())
                    .add("grant_type", "client_credentials")
                    .build();

            Request request = new Request.Builder()
                    .url(OAUTH_TOKEN_URL)
                    .post(form)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IllegalStateException(
                            "Flutterwave OAuth failed: HTTP " + response.code() + " " + body);
                }

                JsonNode node = objectMapper.readTree(body);
                String accessToken = node.path("access_token").asString(null);
                long expiresIn = node.path("expires_in").asLong(600);

                if (accessToken == null || accessToken.isBlank()) {
                    throw new IllegalStateException("Flutterwave OAuth missing access_token: " + body);
                }

                cachedAccessToken = accessToken;
                tokenExpiresAtEpochMillis = System.currentTimeMillis()
                        + (expiresIn * 1000) - TOKEN_REFRESH_MARGIN_MILLIS;

                log.info("Flutterwave OAuth token refreshed — expires in {}s", expiresIn);
                return cachedAccessToken;
            }
        } catch (Exception e) {
            log.error("Flutterwave OAuth token refresh failed", e);
            throw new IllegalStateException("Failed to obtain Flutterwave access token: " + e.getMessage(), e);
        } finally {
            tokenLock.unlock();
        }
    }

    // ─── Deposits — mobile money charge ──────────────────────────────────────

    public record CheckoutResult(boolean success, String chargeId, String reference,
                                  String nextActionType, String redirectUrl,
                                  String paymentInstructionNote, String message) {}

    /**
     * Flutterwave v4 General Flow for mobile money deposit:
     *
     * Step 1: POST /customers            — create/get customer
     * Step 2: POST /payment-methods      — create mobile_money payment method
     * Step 3: POST /charges              — initiate charge
     *
     * next_action.type can be:
     *   "payment_instruction"  — show the note, user approves on phone
     *   "redirect_url"         — redirect user to URL returned
     *
     * countryCode: dialling code e.g. "233" for Ghana
     * network: e.g. "MTN", "airtel", "Mpesa" — case as per Flutterwave docs
     * currency: MUST be the local currency for the target network/country
     *   (e.g. "GHS" for Ghana MTN) — per Flutterwave's Mobile Money docs,
     *   "the charge currency must match the currency_code used when
     *   creating the payment method." There is no USD-denominated mobile
     *   money charge; a mismatched currency is rejected outright with
     *   REQUEST_NOT_VALID.
     *
     * Idempotency keys for the three sub-steps are all derived from
     * `reference` (see class javadoc) so a retried call with the same
     * reference dedupes correctly instead of creating duplicate
     * customers/payment-methods/charges.
     */
    public CheckoutResult initiateMobileMoneyCharge(BigDecimal amount, String currency, String reference,
                                                     String customerEmail, String customerName,
                                                     String countryCode, String network,
                                                     String phoneNumber) {
        try {
            String customerId = createOrGetCustomer(customerEmail, customerName, countryCode, phoneNumber, reference);

            // Step 2: Create mobile_money payment method
            Map<String, Object> mobileMoney = new HashMap<>();
            mobileMoney.put("country_code", countryCode);
            mobileMoney.put("network", network);
            mobileMoney.put("phone_number", phoneNumber);

            Map<String, Object> pmBody = new HashMap<>();
            pmBody.put("type", "mobile_money");
            pmBody.put("mobile_money", mobileMoney);

            String pmResponse = post("/payment-methods", pmBody, reference + "-paymentmethod");
            JsonNode pmNode = objectMapper.readTree(pmResponse);

            if (!"success".equals(pmNode.path("status").asString(""))) {
                String msg = pmNode.path("message").asString("Failed to create payment method");
                log.warn("Flutterwave payment-method creation failed: reference={} message={}", reference, msg);
                return new CheckoutResult(false, null, reference, null, null, null, msg);
            }

            String paymentMethodId = pmNode.path("data").path("id").asString(null);

            // Step 3: Create charge
            Map<String, Object> chargeBody = new HashMap<>();
            chargeBody.put("reference", reference);
            chargeBody.put("currency", currency.toUpperCase());
            chargeBody.put("customer_id", customerId);
            chargeBody.put("payment_method_id", paymentMethodId);
            chargeBody.put("amount", amount);
            if (config.getRedirectUrl() != null && !config.getRedirectUrl().isBlank()) {
                chargeBody.put("redirect_url", config.getRedirectUrl());
            }

            // Charge itself is keyed on the caller's own reference — this is
            // the true dedup unit: a retried deposit with the same reference
            // must return the original charge, not create a second one.
            String chargeResponse = post("/charges", chargeBody, reference);
            log.info("Flutterwave charge response: reference={} body={}", reference, chargeResponse);
            JsonNode chargeNode = objectMapper.readTree(chargeResponse);

            String envelopeStatus = chargeNode.path("status").asString("");
            if (!"success".equals(envelopeStatus) && !"pending".equals(envelopeStatus)) {
                String msg = chargeNode.path("message").asString("Unknown Flutterwave error");
                log.warn("Flutterwave charge rejected: reference={} message={}", reference, msg);
                return new CheckoutResult(false, null, reference, null, null, null, msg);
            }

            JsonNode data = chargeNode.path("data");
            String chargeId = data.path("id").asString(null);
            JsonNode nextAction = data.path("next_action");
            String nextActionType = nextAction.path("type").asString(null);

            // "redirect_url" next_action
            String redirectUrl = null;
            if ("redirect_url".equals(nextActionType)) {
                redirectUrl = nextAction.path("redirect_url").path("url").asString(null);
            }

            // "payment_instruction" next_action
            String instructionNote = null;
            if ("payment_instruction".equals(nextActionType)) {
                instructionNote = nextAction.path("payment_instruction").path("note").asString(null);
            }

            log.info("Flutterwave charge created: reference={} chargeId={} nextAction={}",
                    reference, chargeId, nextActionType);

            return new CheckoutResult(true, chargeId, reference, nextActionType,
                    redirectUrl, instructionNote, "Charge created");

        } catch (Exception e) {
            log.error("Flutterwave charge initiation failed: reference={}", reference, e);
            return new CheckoutResult(false, null, reference, null, null, null,
                    "Charge initiation failed: " + e.getMessage());
        }
    }

    /**
     * Creates a Flutterwave customer.
     * Only email is required; name and phone improve customer profiling.
     */
    private String createOrGetCustomer(String email, String name, String countryCode,
                                        String phoneNumber, String idempotencyKey) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);

        if (name != null && !name.isBlank()) {
            String[] parts = name.trim().split("\\s+", 2);
            Map<String, Object> nameMap = new HashMap<>();
            nameMap.put("first", parts[0]);
            if (parts.length > 1) nameMap.put("last", parts[1]);
            body.put("name", nameMap);
        }

        if (phoneNumber != null && !phoneNumber.isBlank()) {
            Map<String, Object> phone = new HashMap<>();
            phone.put("country_code", countryCode);
            phone.put("number", phoneNumber);
            body.put("phone", phone);
        }

        String responseBody = post("/customers", body, idempotencyKey + "-customer");
        JsonNode node = objectMapper.readTree(responseBody);

        if (!"success".equals(node.path("status").asString(""))) {
            throw new IllegalStateException("Failed to create Flutterwave customer: " + responseBody);
        }

        return node.path("data").path("id").asString(null);
    }

    // ─── Verify a charge ──────────────────────────────────────────────────────

    public record VerifyResult(boolean success, String status, String chargeId, String reference,
                                BigDecimal amount, String currency, String customerEmail,
                                String message) {}

    /**
     * Verifies a charge by Flutterwave's charge id (chg_xxx).
     * GET /charges/{id}
     *
     * status values from docs: "pending" | "succeeded" | "failed"
     */
    public VerifyResult verifyChargeById(String chargeId) {
        try {
            String responseBody = get("/charges/" + chargeId);
            log.info("Flutterwave verify charge: chargeId={} response={}", chargeId, responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            if (!"success".equals(node.path("status").asString(""))) {
                String msg = node.path("message").asString("Unknown error");
                return new VerifyResult(false, null, chargeId, null, null, null, null, msg);
            }

            JsonNode data = node.path("data");
            String status = data.path("status").asString("");
            String reference = data.path("reference").asString(null);
            BigDecimal amount = data.path("amount").isMissingNode() ? null
                    : data.path("amount").decimalValue();
            String currency = data.path("currency").asString(null);
            String customerEmail = data.path("customer").path("email").asString(null);

            boolean success = "succeeded".equalsIgnoreCase(status);
            return new VerifyResult(success, status, chargeId, reference, amount, currency,
                    customerEmail, "Charge status: " + status);

        } catch (Exception e) {
            log.error("Flutterwave charge verification failed: chargeId={}", chargeId, e);
            return new VerifyResult(false, null, chargeId, null, null, null, null,
                    "Verification failed: " + e.getMessage());
        }
    }

    // ─── Disbursements — direct transfer ─────────────────────────────────────

    public record TransferResult(boolean success, String message, String transferId, String reference) {}

    /**
     * Initiates a mobile money transfer using Flutterwave v4 direct transfer.
     *
     * Endpoint: POST /direct-transfers
     *
     * From the docs the correct structure is:
     * {
     *   "action": "instant",
     *   "type": "mobile_money",
     *   "reference": "...",
     *   "narration": "...",
     *   "payment_instruction": {
     *     "source_currency": "...",       <- your Flutterwave balance currency
     *     "destination_currency": "KES",  <- recipient's currency
     *     "amount": { "applies_to": "destination_currency", "value": 1000 },
     *     "recipient": {
     *       "name": { "first": "John", "last": "Doe" },
     *       "mobile_money": {
     *         "network": "Mpesa",
     *         "msisdn": "2547XXXXXXXX"    <- must include country code
     *       }
     *     }
     *   }
     * }
     *
     * Response status will be "NEW" — final status comes via webhook "transfer.disburse"
     * Webhook data.status: "SUCCESSFUL" | "FAILED"
     *
     * Idempotency key = reference (the caller's own reference) — see class javadoc.
     */
    public TransferResult initiateTransfer(String msisdn, String network,
                                            String sourceCurrency, String destinationCurrency,
                                            BigDecimal amount, String reference, String narration,
                                            String beneficiaryFirstName, String beneficiaryLastName) {

        if (amount.compareTo(config.getTransfer().getMinAmount()) < 0
                || amount.compareTo(config.getTransfer().getMaxAmount()) > 0) {
            return new TransferResult(false,
                    "Amount must be between " + config.getTransfer().getMinAmount()
                            + " and " + config.getTransfer().getMaxAmount() + " " + destinationCurrency,
                    null, reference);
        }

        try {
            Map<String, Object> amountMap = new HashMap<>();
            amountMap.put("applies_to", "destination_currency");
            amountMap.put("value", amount);

            Map<String, Object> mobileMoney = new HashMap<>();
            mobileMoney.put("network", network);
            mobileMoney.put("msisdn", msisdn);  // must include country code e.g. 2547XXXXXXXX

            Map<String, Object> nameMap = new HashMap<>();
            nameMap.put("first", beneficiaryFirstName != null ? beneficiaryFirstName : "");
            if (beneficiaryLastName != null && !beneficiaryLastName.isBlank()) {
                nameMap.put("last", beneficiaryLastName);
            }

            Map<String, Object> recipient = new HashMap<>();
            recipient.put("name", nameMap);
            recipient.put("mobile_money", mobileMoney);

            Map<String, Object> paymentInstruction = new HashMap<>();
            paymentInstruction.put("source_currency", sourceCurrency.toUpperCase());
            paymentInstruction.put("destination_currency", destinationCurrency.toUpperCase());
            paymentInstruction.put("amount", amountMap);
            paymentInstruction.put("recipient", recipient);

            Map<String, Object> body = new HashMap<>();
            body.put("action", "instant");
            body.put("type", "mobile_money");
            body.put("reference", reference);
            body.put("narration", narration != null ? narration : "Premisave wallet disbursement");
            body.put("payment_instruction", paymentInstruction);

            String responseBody = post("/direct-transfers", body, reference);
            log.info("Flutterwave direct-transfer response: reference={} body={}", reference, responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            String envelopeStatus = node.path("status").asString("");
            if (!"success".equals(envelopeStatus)) {
                String msg = node.path("message").asString("Unknown transfer error");
                log.warn("Flutterwave transfer rejected: reference={} message={}", reference, msg);
                return new TransferResult(false, msg, null, reference);
            }

            JsonNode data = node.path("data");
            String transferId = data.path("id").asString(null);
            // Status is always "NEW" on creation — final status via webhook
            log.info("Flutterwave transfer initiated: reference={} transferId={}", reference, transferId);
            return new TransferResult(true, "Transfer initiated", transferId, reference);

        } catch (Exception e) {
            log.error("Flutterwave transfer failed: reference={}", reference, e);
            throw new FlutterwaveTransferException(reference, "INITIATION_FAILED",
                    "Flutterwave transfer initiation failed: " + e.getMessage());
        }
    }

    /**
     * Initiates a bank transfer using Flutterwave v4 direct transfer.
     *
     * Endpoint: POST /direct-transfers, type="bank"
     *
     * {
     *   "action": "instant",
     *   "type": "bank",
     *   "reference": "...",
     *   "narration": "...",
     *   "payment_instruction": {
     *     "source_currency": "...",
     *     "destination_currency": "...",
     *     "amount": { "applies_to": "destination_currency", "value": 1000 },
     *     "recipient": {
     *       "bank": { "code": "...", "account_number": "..." },
     *       "name": { "first": "...", "last": "..." }
     *     }
     *   }
     * }
     *
     * NOTE: some corridors require additional recipient.bank fields beyond
     * code/account_number — e.g. GHS needs "branch", INR needs "branch",
     * EGP needs recipient.national_identification, USD/AUD/EUR corridors
     * need routing_number/swift_code/account_type instead of "code". This
     * method currently sends only code + account_number, which matches the
     * simplest documented sample (NGN bank payout). Extend this — and check
     * GET /banks/{country} plus the docs' per-currency sample for your
     * target country — before enabling BANK transfers for any corridor
     * that needs more than code + account_number.
     *
     * Response status will be "NEW" on creation — final status comes via
     * the "transfer.disburse" webhook, same as mobile money transfers.
     * Idempotency key = reference — see class javadoc.
     */
    public TransferResult initiateBankTransfer(String accountNumber, String bankCode,
                                                String sourceCurrency, String destinationCurrency,
                                                BigDecimal amount, String reference, String narration,
                                                String beneficiaryFirstName, String beneficiaryLastName) {

        if (amount.compareTo(config.getTransfer().getMinAmount()) < 0
                || amount.compareTo(config.getTransfer().getMaxAmount()) > 0) {
            return new TransferResult(false,
                    "Amount must be between " + config.getTransfer().getMinAmount()
                            + " and " + config.getTransfer().getMaxAmount() + " " + destinationCurrency,
                    null, reference);
        }

        try {
            Map<String, Object> amountMap = new HashMap<>();
            amountMap.put("applies_to", "destination_currency");
            amountMap.put("value", amount);

            Map<String, Object> bank = new HashMap<>();
            bank.put("code", bankCode);
            bank.put("account_number", accountNumber);

            Map<String, Object> nameMap = new HashMap<>();
            nameMap.put("first", beneficiaryFirstName != null ? beneficiaryFirstName : "");
            if (beneficiaryLastName != null && !beneficiaryLastName.isBlank()) {
                nameMap.put("last", beneficiaryLastName);
            }

            Map<String, Object> recipient = new HashMap<>();
            recipient.put("name", nameMap);
            recipient.put("bank", bank);

            Map<String, Object> paymentInstruction = new HashMap<>();
            paymentInstruction.put("source_currency", sourceCurrency.toUpperCase());
            paymentInstruction.put("destination_currency", destinationCurrency.toUpperCase());
            paymentInstruction.put("amount", amountMap);
            paymentInstruction.put("recipient", recipient);

            Map<String, Object> body = new HashMap<>();
            body.put("action", "instant");
            body.put("type", "bank");
            body.put("reference", reference);
            body.put("narration", narration != null ? narration : "Premisave wallet disbursement");
            body.put("payment_instruction", paymentInstruction);

            String responseBody = post("/direct-transfers", body, reference);
            log.info("Flutterwave bank-transfer response: reference={} body={}", reference, responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            String envelopeStatus = node.path("status").asString("");
            if (!"success".equals(envelopeStatus)) {
                String msg = node.path("message").asString("Unknown transfer error");
                log.warn("Flutterwave bank transfer rejected: reference={} message={}", reference, msg);
                return new TransferResult(false, msg, null, reference);
            }

            String transferId = node.path("data").path("id").asString(null);
            log.info("Flutterwave bank transfer initiated: reference={} transferId={}", reference, transferId);
            return new TransferResult(true, "Transfer initiated", transferId, reference);

        } catch (Exception e) {
            log.error("Flutterwave bank transfer failed: reference={}", reference, e);
            throw new FlutterwaveTransferException(reference, "INITIATION_FAILED",
                    "Flutterwave bank transfer initiation failed: " + e.getMessage());
        }
    }

    /**
     * Query the status of a transfer.
     * GET /transfers/{id}
     * Used for manual reconciliation.
     */
    public TransferResult getTransferStatus(String transferId) {
        try {
            String responseBody = get("/transfers/" + transferId);
            log.info("Flutterwave transfer status: transferId={} response={}", transferId, responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            if (!"success".equals(node.path("status").asString(""))) {
                return new TransferResult(false,
                        node.path("message").asString("Unknown error"), transferId, null);
            }

            JsonNode data = node.path("data");
            String status = data.path("status").asString("");
            String reference = data.path("reference").asString(null);

            boolean success = "SUCCESSFUL".equalsIgnoreCase(status);
            return new TransferResult(success, "Transfer status: " + status, transferId, reference);

        } catch (Exception e) {
            log.error("Flutterwave transfer status query failed: transferId={}", transferId, e);
            return new TransferResult(false, "Query failed: " + e.getMessage(), transferId, null);
        }
    }

    // ─── Account Linking ──────────────────────────────────────────────────────

    /**
     * Links a Flutterwave account to the wallet.
     * Caller must save the wallet after calling this.
     */
    public void linkFlutterwaveAccount(com.premisave.wallet.entity.Wallet wallet, String customerId,
                                        String paymentMethodId, String network, String phone) {
        wallet.setFlutterwaveCustomerId(customerId);
        wallet.setFlutterwavePaymentMethodId(paymentMethodId);
        wallet.setFlutterwavePaymentMethodNetwork(network);
        wallet.setFlutterwavePaymentMethodPhone(phone);
        wallet.setPendingFlutterwaveSetupTokenId(null);
        log.info("Flutterwave account linked: customerId={}", customerId);
    }

    /**
     * Unlinks a Flutterwave account from the wallet.
     * Caller must save the wallet after calling this.
     */
    public void unlinkAccount(com.premisave.wallet.entity.Wallet wallet) {
        wallet.setFlutterwaveCustomerId(null);
        wallet.setFlutterwavePaymentMethodId(null);
        wallet.setFlutterwavePaymentMethodNetwork(null);
        wallet.setFlutterwavePaymentMethodPhone(null);
        wallet.setPendingFlutterwaveSetupTokenId(null);
        log.info("Flutterwave account unlinked");
    }

    // ─── Platform balance ────────────────────────────────────────────────────

    public record CurrencyBalanceEntry(String currency, Map<String, BigDecimal> amounts) {}
    public record BalanceResult(boolean success, List<CurrencyBalanceEntry> balances, String message) {}

    /**
     * Premisave's OWN Flutterwave balance, across every currency wallet.
     *
     * GET /wallets/balances — CONFIRMED against Flutterwave's own official
     * OpenAPI spec (developer.flutterwave.com/reference/fetch_wallet_balances.md),
     * not a guess. An earlier version of this method used GET /wallets,
     * which real testing confirmed returns 404 RESOURCE_NOT_FOUND — this
     * is the actual documented path.
     *
     * Response shape per the spec: {"status", "message", "data": [{"currency",
     * "available_balance"}]} — data is a FLAT array, not nested under a
     * "wallets" key, and there is no "ledger_balance" field at all — an
     * earlier version of this method assumed both incorrectly.
     *
     * Error shape (400/401/403, and the 404 seen in real testing follows
     * the same pattern): {"status":"failed","error":{"type","code","message"}}
     * — the real message is ALWAYS under error.message, never top-level
     * message. An earlier version of this method only checked top-level
     * message, which is why a real "Resource not found" error surfaced
     * here as a generic "Unknown error" instead.
     *
     * The spec's server entry (developersandbox-api.flutterwave.com)
     * confirms this class's existing base URL configuration was already
     * correct — only the path itself was wrong.
     */
    public BalanceResult getBalance() {
        try {
            String responseBody = get("/wallets/balances");
            log.info("Flutterwave balance response: {}", responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            if (!"success".equals(node.path("status").asString(""))) {
                String msg = node.path("error").path("message").asString(null);
                if (msg == null || msg.isBlank()) {
                    msg = node.path("message").asString("Unknown error");
                }
                return new BalanceResult(false, List.of(), "Flutterwave getBalance failed: " + msg);
            }

            List<CurrencyBalanceEntry> result = new ArrayList<>();
            for (JsonNode wallet : node.path("data")) {
                String currency = wallet.path("currency").asString(null);
                if (currency == null) {
                    continue;
                }
                Map<String, BigDecimal> amounts = new LinkedHashMap<>();
                if (wallet.has("available_balance")) {
                    amounts.put("available", wallet.path("available_balance").decimalValue());
                }
                result.add(new CurrencyBalanceEntry(currency, amounts));
            }

            return new BalanceResult(true, result, "OK");
        } catch (Exception e) {
            log.error("Flutterwave getBalance failed", e);
            return new BalanceResult(false, List.of(), "Flutterwave getBalance failed: " + e.getMessage());
        }
    }

    // ─── Webhook signature verification ──────────────────────────────────────

    /**
     * Verifies a Flutterwave v4 webhook signature.
     * The signature is HMAC-SHA256 of the raw body, base64-encoded,
     * sent in the "flutterwave-signature" header.
     */
    public boolean verifyWebhookSignature(String rawBody, String receivedSignature) {
        if (config.getWebhookSecretHash() == null || config.getWebhookSecretHash().isBlank()) {
            log.error("flutterwave.webhook-secret-hash is not configured — rejecting webhook");
            return false;
        }
        if (receivedSignature == null || rawBody == null) {
            log.warn("Flutterwave webhook rejected — missing signature or body");
            return false;
        }
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(
                    config.getWebhookSecretHash().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = hmac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String computedSignature = Base64.getEncoder().encodeToString(computed);
            return computedSignature.equals(receivedSignature);
        } catch (Exception e) {
            log.error("Flutterwave webhook signature verification failed", e);
            return false;
        }
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    /**
     * @param idempotencyKey MUST be stable across retries of the same
     *                       logical operation (derived from the caller's
     *                       own reference) — never a freshly randomized
     *                       value. See class javadoc.
     */
    private String post(String path, Map<String, Object> body, String idempotencyKey) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));
        Request.Builder builder = new Request.Builder()
                .url(config.baseUrl() + path)
                .addHeader("Authorization", "Bearer " + getAccessToken())
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Trace-Id", UUID.randomUUID().toString())
                .addHeader("X-Idempotency-Key", idempotencyKey);

        // SANDBOX TESTING ONLY — see FlutterwaveConfig.sandboxScenarioKey javadoc.
        if (config.getSandboxScenarioKey() != null && !config.getSandboxScenarioKey().isBlank()) {
            builder.addHeader("X-Scenario-Key", config.getSandboxScenarioKey());
        }

        Request request = builder.post(rb).build();
        try (Response response = http.newCall(request).execute()) {
            return response.body() != null ? response.body().string() : "";
        }
    }

    private String get(String path) throws Exception {
        Request request = new Request.Builder()
                .url(config.baseUrl() + path)
                .addHeader("Authorization", "Bearer " + getAccessToken())
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Trace-Id", UUID.randomUUID().toString())
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            return response.body() != null ? response.body().string() : "";
        }
    }
}