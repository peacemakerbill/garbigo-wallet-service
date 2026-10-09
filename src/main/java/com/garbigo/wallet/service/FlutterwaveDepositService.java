package com.premisave.wallet.service;

import com.premisave.wallet.dto.DepositRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DepositStatus;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DepositRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Flutterwave deposit business logic — mobile-money charge initiation and
 * webhook/redirect reconciliation. Split out of the former all-providers
 * DepositService, mirroring FlutterwaveService's existing role at the
 * API-integration layer.
 *
 * Migrated to the Deposit entity (Stage 3) — same pattern
 * NowPaymentsDepositService pioneered (Stage 2): a dedicated Deposit
 * record instead of a generic Transaction row with detail packed into a
 * free-text description, plus DepositTransactionRecorder creating the
 * matching Transaction row on confirmation for the unified history feed.
 *
 * CURRENCY CONVERSION: the wallet is now fixed at USD (see Wallet.currency),
 * so the conversion TARGET changed from KES to USD throughout this file —
 * this used to convert local currency -> KES; it now converts local
 * currency -> USD via ExchangeRateService (the Redis/MongoDB-cached
 * layer), which supports any ISO 4217 currency pair Frankfurter itself
 * covers — not a fixed list — so every local currency (GHS, UGX, KES,
 * anything else) routes through the same cached, resilient path. See
 * getRateToUsd() below.
 *
 * Called from DepositService.initiateDeposit (dispatcher) for initiation,
 * and directly from WalletController/PaymentCallbackController for the
 * confirm endpoint and webhook handler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlutterwaveDepositService {

    private final WalletRepository walletRepository;
    private final DepositRepository depositRepository;
    private final FlutterwaveService flutterwaveService;
    private final ExchangeRateService exchangeRateService;
    private final DepositTransactionRecorder depositTransactionRecorder;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;

    /**
     * Initiates a Flutterwave mobile-money deposit via v4's General Flow.
     *
     * CURRENCY: per Flutterwave's Mobile Money docs, "the charge currency
     * must match the currency_code used when creating the payment method."
     * There is no USD-denominated mobile money charge — a mismatched
     * currency is rejected outright (REQUEST_NOT_VALID). This path
     * therefore requires the caller to supply the correct LOCAL currency
     * for the target network/country (e.g. GHS for Ghana MTN, UGX for
     * Uganda, etc. — NOT KES, since Kenyan mobile money goes through the
     * direct M-Pesa STK push path instead, see provider=MPESA).
     *
     * The wallet is fixed at USD, so the (cached, resilient) FX rate from
     * the local currency to USD is used to compute what actually gets
     * credited to the wallet — see getRateToUsd() below, which now routes
     * ANY local currency through ExchangeRateService rather than a fixed
     * subset. The rate is logged for reconciliation against the eventual
     * webhook payout amount. Stored as Deposit.priceAmount/priceCurrency
     * — same fields NowPaymentsDepositService populates for its own
     * fiat-pricing case, reused here since the underlying concept (amount
     * priced in a non-USD currency, converted once at initiation) is
     * identical.
     *
     * chargeId is stored as Deposit.providerReference immediately — v4
     * only supports verifying a charge by ITS OWN id (GET /charges/{id}),
     * not by our own reference the way v3's verify_by_reference worked,
     * so confirmFlutterwaveDeposit below needs it on record.
     *
     * REQUIRES two fields on DepositRequest: flutterwaveCountryCode
     * (e.g. "233") and flutterwaveMobileNetwork (e.g. "MTN") — see
     * DepositRequest.java. customerName/customerPhone are expected to
     * already exist on that DTO. request.currency is now required and
     * must be the local currency described above.
     */
    public PaymentResponse initiateFlutterwaveDeposit(String userId, String userEmail, DepositRequest request,
                                                        Wallet wallet, String idempotencyKey) {
        String localCurrency = request.getCurrency() != null ? request.getCurrency().toUpperCase() : null;
        if (localCurrency == null || localCurrency.isBlank()) {
            throw new IllegalArgumentException(
                    "currency is required for Flutterwave mobile-money deposits — must be the local currency "
                            + "for the target network/country (e.g. GHS for Ghana MTN), not USD.");
        }
        if (request.getCustomerPhone() == null || request.getCustomerPhone().isBlank()) {
            throw new IllegalArgumentException("customerPhone is required for Flutterwave mobile-money deposits");
        }
        if (request.getFlutterwaveCountryCode() == null || request.getFlutterwaveCountryCode().isBlank()) {
            throw new IllegalArgumentException("flutterwaveCountryCode (e.g., \"233\" for Ghana) is required");
        }
        if (request.getFlutterwaveMobileNetwork() == null || request.getFlutterwaveMobileNetwork().isBlank()) {
            throw new IllegalArgumentException("flutterwaveMobileNetwork (e.g., \"MTN\") is required");
        }

        BigDecimal chargeAmount = request.getAmount(); // amount in localCurrency
        BigDecimal fxRate = getRateToUsd(localCurrency);
        BigDecimal usdEquivalent = "USD".equals(localCurrency)
                ? chargeAmount
                : chargeAmount.multiply(fxRate).setScale(2, RoundingMode.HALF_UP);

        String txRef = idempotencyKey;
        String customerName = request.getCustomerName() != null ? request.getCustomerName() : userEmail;

        FlutterwaveService.CheckoutResult result = flutterwaveService.initiateMobileMoneyCharge(
                chargeAmount, localCurrency, txRef, userEmail, customerName,
                request.getFlutterwaveCountryCode(), request.getFlutterwaveMobileNetwork(),
                request.getCustomerPhone());

        if (!result.success()) {
            log.warn("Flutterwave charge initiation rejected: userId={} reason={}", userId, result.message());
            return new PaymentResponse(false, null, "Flutterwave charge initiation failed: " + result.message());
        }

        log.info("Flutterwave charge created: userId={} txRef={} chargeId={} localAmount={} {} usdEquivalent={} rate={} nextAction={}",
                userId, txRef, result.chargeId(), chargeAmount, localCurrency, usdEquivalent, fxRate, result.nextActionType());

        Deposit deposit = new Deposit();
        deposit.setUserId(userId);
        deposit.setWalletId(wallet.getId());
        deposit.setAmount(usdEquivalent);
        deposit.setCurrency(Currency.USD);
        deposit.setProvider("FLUTTERWAVE");
        deposit.setChannel("FLUTTERWAVE_MOBILE_MONEY");
        deposit.setSource(request.getCustomerPhone());
        deposit.setStatus(DepositStatus.PENDING);
        deposit.setReference(txRef);
        deposit.setProviderReference(result.chargeId());
        deposit.setPriceAmount(chargeAmount);
        deposit.setPriceCurrency(localCurrency.toLowerCase());
        depositRepository.save(deposit);

        // Return the redirect URL from Flutterwave's next_action, if present.
        // Otherwise, return payment_instruction if it's a prompt-on-phone scenario.
        String redirectUrl = result.redirectUrl();
        String instructionNote = result.paymentInstructionNote();
        String userFacingMessage = redirectUrl != null
                ? "Redirect to " + redirectUrl + " to authorize the charge."
                : instructionNote != null
                ? "Approve the charge on your phone: " + instructionNote
                : localCurrency + " " + chargeAmount + " charge initiated (USD " + usdEquivalent + ").";

        return new PaymentResponse(true, redirectUrl != null ? redirectUrl : txRef, userFacingMessage);
    }

    @Transactional
    public PaymentResponse confirmFlutterwaveDeposit(String txRef, String callerUserId) {
        Deposit deposit = depositRepository.findByReference(txRef)
                .orElseThrow(() -> new IllegalStateException(
                        "No pending deposit found for Flutterwave txRef=" + txRef));

        if (!deposit.getUserId().equals(callerUserId)) {
            throw new IllegalArgumentException("This Flutterwave charge does not belong to the authenticated user");
        }

        if (deposit.getStatus() == DepositStatus.SUCCESS) {
            return new PaymentResponse(true, deposit.getId(), "Deposit already completed");
        }

        if (deposit.getStatus() == DepositStatus.FAILED) {
            return new PaymentResponse(false, deposit.getId(),
                    "This Flutterwave charge previously failed and cannot be retried with the same reference.");
        }

        String chargeId = deposit.getProviderReference();
        if (chargeId == null) {
            log.error("Flutterwave confirm: pending deposit txRef={} has no chargeId recorded", txRef);
            return new PaymentResponse(false, deposit.getId(),
                    "This charge is in an inconsistent state and needs manual review. Please contact support.");
        }

        FlutterwaveService.VerifyResult verifyResult = flutterwaveService.verifyChargeById(chargeId);
        if (!verifyResult.success()) {
            markFlutterwaveTransactionFailed(txRef, "Charge verification failed: " + verifyResult.message());
            return new PaymentResponse(false, deposit.getId(),
                    "Flutterwave charge verification failed: " + verifyResult.message());
        }

        creditWalletFromFlutterwaveCallback(txRef, chargeId);
        return new PaymentResponse(true, deposit.getId(), "Flutterwave deposit successful");
    }

    /**
     * CRITICAL: Called from two paths:
     *  1. confirmFlutterwaveDeposit (frontend redirect confirm) — after
     *     server-side charge verification (see above).
     *  2. Webhook handler in PaymentCallbackController (charge.completed
     *     event) — after server-side verification by the webhook handler.
     *
     * Both ensure verifyChargeById has been called first, preventing
     * spoofed/rejected charges from crediting the wallet. Safe idempotency:
     * if a charge is already SUCCESS, this method no-ops. The credited
     * amount always comes from the deposit record created at initiation
     * time (computed from a live/cached FX rate at that moment, already
     * in USD) — this method only takes txRef and a provider reference for
     * the audit trail, not an amount, so no conversion happens here.
     */
    @Transactional
    public void creditWalletFromFlutterwaveCallback(String txRef, String providerReference) {
        Deposit deposit = depositRepository.findByReference(txRef).orElse(null);

        if (deposit == null) {
            log.warn("Flutterwave reconciliation: no pending deposit found for txRef={} (providerReference={}) — " +
                    "cannot credit; needs manual review", txRef, providerReference);
            return;
        }

        if (deposit.getStatus() == DepositStatus.SUCCESS) {
            log.info("Flutterwave deposit already processed for txRef={} — skipping duplicate credit", txRef);
            return;
        }

        if (deposit.getStatus() == DepositStatus.FAILED) {
            log.warn("Flutterwave credit attempted for previously-failed txRef={} — ignoring, needs manual review", txRef);
            return;
        }

        Wallet wallet = walletRepository.findById(deposit.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + deposit.getWalletId()));

        wallet.setBalance(wallet.getBalance().add(deposit.getAmount()));
        walletRepository.save(wallet);

        deposit.setStatus(DepositStatus.SUCCESS);
        deposit.setProviderReference(providerReference);
        String recipientName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
        deposit.setRecipientName(recipientName);
        depositRepository.save(deposit);

        depositTransactionRecorder.record(deposit.getUserId(), deposit.getWalletId(), deposit.getAmount(),
                deposit, deposit.getReference());

        // Exchange rate info derived from the two values already on the
        // deposit record (amount = priceAmount * rate, from initiation
        // time) rather than re-fetched — this stays consistent with
        // whatever rate was actually applied when this specific deposit
        // was converted, not whatever the live/cached rate happens to be
        // right now.
        String exchangeRateInfo = null;
        if (deposit.getPriceAmount() != null && deposit.getPriceAmount().compareTo(BigDecimal.ZERO) != 0
                && deposit.getPriceCurrency() != null) {
            BigDecimal impliedRate = deposit.getAmount()
                    .divide(deposit.getPriceAmount(), 6, RoundingMode.HALF_UP);
            exchangeRateInfo = "1 " + deposit.getPriceCurrency().toUpperCase() + " = " + impliedRate.toPlainString() + " USD";
        }

        emailService.sendDepositConfirmation(wallet.getAccountNumber(), deposit.getAmount().toPlainString(),
                deposit.getCurrency().name(), deposit.getReference(), wallet.getBalance().toPlainString(),
                new EmailService.DepositDetails("Flutterwave", exchangeRateInfo, deposit.getSource(),
                        null, providerReference, recipientName, wallet.getAccountNumber(), wallet.getId()));

        log.info("Wallet credited via Flutterwave: txRef={} amount={} providerReference={} (from initiationRate)",
                txRef, deposit.getAmount(), providerReference);
    }

    @Transactional
    public void markFlutterwaveTransactionFailed(String txRef, String reason) {
        depositRepository.findByReference(txRef).ifPresentOrElse(deposit -> {
            if (deposit.getStatus() == DepositStatus.SUCCESS) {
                log.warn("Ignoring failure callback for already-completed Flutterwave txRef={}", txRef);
                return;
            }
            deposit.setStatus(DepositStatus.FAILED);
            deposit.setFailureReason(reason);
            depositRepository.save(deposit);
            log.warn("Flutterwave deposit failed: txRef={} reason={}", txRef, reason);
        }, () -> log.warn("Failure callback for unknown Flutterwave txRef={}: {}", txRef, reason));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * ExchangeRateService now supports ANY ISO 4217 currency pair
     * Frankfurter itself covers — it saves a genuinely new pair the
     * first time it's requested rather than being limited to a fixed
     * list, so the earlier KES/EUR-only special-casing (with a fallback
     * to FxRateService's live call directly for anything else) is no
     * longer needed. Every local currency now routes through the same
     * cached, resilient path.
     */
    private BigDecimal getRateToUsd(String currency) {
        if ("USD".equals(currency)) {
            return BigDecimal.ONE;
        }
        return exchangeRateService.getRate(currency, "USD");
    }
}