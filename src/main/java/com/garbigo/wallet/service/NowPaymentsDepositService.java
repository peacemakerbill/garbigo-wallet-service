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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * NOWPayments (crypto) deposit business logic — mirrors
 * FlutterwaveDepositService's structure, one level up from
 * NowPaymentsService's raw API calls.
 *
 * PILOT for the Deposit entity (Stage 2) — the first deposit service
 * rewritten to use Deposit instead of Transaction directly, mirroring how
 * every provider's disbursement service already uses Disbursement. Every
 * public method here keeps its exact original signature, so
 * PaymentCallbackController's webhook handler needed zero changes for
 * this rewrite. MpesaDepositService/StripeDepositService/
 * PaypalDepositService/FlutterwaveDepositService still deal in Transaction
 * directly — not yet migrated to this same pattern.
 *
 * Unlike every other provider here, NOWPayments deposits can be priced
 * in ANY fiat currency the client chooses (via
 * request.getNowPaymentsPriceCurrency(), defaulting to "usd"), while
 * NOWPayments itself separately quotes the crypto-equivalent amount at
 * checkout time. When the price currency isn't already USD, this class
 * converts it to USD (via ExchangeRateService) before crediting the
 * wallet — the wallet is fixed at USD, so that's the only conversion
 * target that's ever correct here, regardless of what fiat currency the
 * customer chose to be priced in.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NowPaymentsDepositService {

    private final WalletRepository walletRepository;
    private final DepositRepository depositRepository;
    private final NowPaymentsService nowPaymentsService;
    private final ExchangeRateService exchangeRateService;
    private final DepositTransactionRecorder depositTransactionRecorder;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;

    /**
     * Initiates a crypto deposit. TWO separate currency fields are in play
     * here, deliberately not the same thing:
     *
     * request.getCurrency() = the CRYPTOCURRENCY the customer pays in
     * (e.g. "usdttrc20", "btc").
     *
     * request.getNowPaymentsPriceCurrency() = the FIAT currency the amount
     * is denominated in for pricing (defaults to "usd").
     *
     * request.getAmount() is priced in THAT fiat currency and sent to
     * NOWPayments as-is (so their quote reflects exactly what the customer
     * agreed to pay), then SEPARATELY converted to USD via
     * ExchangeRateService — skipped entirely if the price currency is
     * already USD — and that converted figure is what actually gets
     * stored as Deposit.amount and credited to the wallet on
     * confirmation. Same "compute the converted amount once at
     * initiation, credit that fixed figure on confirmation" pattern
     * already used by disburseStripe/disbursePaypal on the withdrawal
     * side.
     *
     * order_id is set to the idempotencyKey, same role as every other
     * provider's txRef/reference — this is what the IPN webhook and any
     * manual status check key back off of.
     *
     * request.getNowPaymentsSandboxCase() (sandbox only) is forwarded
     * straight through to NOWPayments' Create Payment "case" parameter —
     * set it to "success"/"failed"/"partially_paid" to get an instant
     * synthetic IPN callback simulating that outcome, no real crypto
     * required. Null in production, where it's simply not sent.
     *
     * The five NOWPayments-specific fields (payAddress, payAmount,
     * payCurrency, priceAmount, priceCurrency) are now real structured
     * Deposit fields — previously all five were concatenated into a
     * single free-text Transaction.description string.
     */
    public PaymentResponse initiateNowPaymentsDeposit(String userId, DepositRequest request, Wallet wallet,
                                                        String idempotencyKey) {
        String payCurrency = request.getCurrency() != null ? request.getCurrency().toLowerCase() : null;
        if (payCurrency == null || payCurrency.isBlank()) {
            throw new IllegalArgumentException(
                    "currency is required for NOWPayments deposits — the CRYPTOCURRENCY the customer will pay in "
                            + "(e.g. \"usdttrc20\", \"btc\"), not the wallet's USD balance currency.");
        }

        String priceCurrency = request.getNowPaymentsPriceCurrency() != null
                && !request.getNowPaymentsPriceCurrency().isBlank()
                ? request.getNowPaymentsPriceCurrency().toLowerCase()
                : "usd";

        BigDecimal usdAmount;
        if ("usd".equals(priceCurrency)) {
            usdAmount = request.getAmount();
        } else {
            BigDecimal rateToUsd = exchangeRateService.getRate(priceCurrency.toUpperCase(), "USD");
            usdAmount = request.getAmount().multiply(rateToUsd).setScale(2, java.math.RoundingMode.HALF_UP);
        }

        NowPaymentsService.CreatePaymentResult result = nowPaymentsService.createPayment(
                request.getAmount(), priceCurrency, payCurrency, idempotencyKey, "Premisave wallet deposit",
                request.getNowPaymentsSandboxCase());

        if (!result.success()) {
            log.warn("NOWPayments payment creation rejected: userId={} reason={}", userId, result.message());
            return new PaymentResponse(false, null, "NOWPayments payment creation failed: " + result.message());
        }

        log.info("NOWPayments deposit priced: userId={} priceAmount={} {} usdEquivalent={}",
                userId, request.getAmount(), priceCurrency.toUpperCase(), usdAmount);

        Deposit deposit = new Deposit();
        deposit.setUserId(userId);
        deposit.setWalletId(wallet.getId());
        deposit.setAmount(usdAmount); // USD-converted amount, credited as-is on confirmation
        deposit.setCurrency(Currency.USD);
        deposit.setProvider("NOWPAYMENTS");
        deposit.setChannel("NOWPAYMENTS_CRYPTO");
        deposit.setStatus(DepositStatus.PENDING);
        deposit.setReference(idempotencyKey);
        deposit.setProviderReference(result.paymentId());
        deposit.setPayAddress(result.payAddress());
        deposit.setPayAmount(result.payAmount());
        deposit.setPayCurrency(result.payCurrency());
        deposit.setPriceAmount(request.getAmount());
        deposit.setPriceCurrency(priceCurrency);
        depositRepository.save(deposit);

        return new PaymentResponse(true, result.paymentId(),
                "Send " + result.payAmount() + " " + result.payCurrency() + " to " + result.payAddress()
                        + " to complete this deposit.");
    }

    /**
     * Called from the IPN webhook when payment_status is "finished" — the
     * terminal success state (funds converted and sent to your outcome
     * wallet). "confirmed" is deliberately NOT treated as success here: it
     * means the blockchain transaction is confirmed but NOWPayments hasn't
     * yet converted/settled it — same "wait for the actual terminal state,
     * not an intermediate one" reasoning already applied to every other
     * provider's webhook handling in this codebase.
     *
     * Same two-record pattern every completeXDisbursement method uses:
     * updates Deposit (the rich lifecycle record) AND calls
     * DepositTransactionRecorder to create the matching Transaction row
     * for the unified history feed — Deposit alone doesn't feed that.
     */
    @Transactional
    public void creditWalletFromNowPaymentsCallback(String orderId, String paymentId) {
        Deposit deposit = depositRepository.findByReference(orderId).orElse(null);

        if (deposit == null) {
            log.warn("NOWPayments IPN: no pending deposit found for orderId={} (paymentId={}) — cannot credit; needs manual review",
                    orderId, paymentId);
            return;
        }

        if (deposit.getStatus() == DepositStatus.SUCCESS) {
            log.info("NOWPayments deposit already processed for orderId={} — skipping duplicate credit", orderId);
            return;
        }

        if (deposit.getStatus() == DepositStatus.FAILED) {
            log.warn("NOWPayments credit attempted for previously-failed orderId={} — ignoring, needs manual review", orderId);
            return;
        }

        Wallet wallet = walletRepository.findById(deposit.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + deposit.getWalletId()));

        wallet.setBalance(wallet.getBalance().add(deposit.getAmount()));
        walletRepository.save(wallet);

        deposit.setStatus(DepositStatus.SUCCESS);
        deposit.setProviderReference(paymentId);
        String recipientName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
        deposit.setRecipientName(recipientName);
        depositRepository.save(deposit);

        depositTransactionRecorder.record(deposit.getUserId(), deposit.getWalletId(), deposit.getAmount(),
                deposit, deposit.getReference());

        // Same derivation as FlutterwaveDepositService — only meaningful
        // if this deposit's priceCurrency differs from USD (NOWPayments
        // can price in a fiat currency while the actual payment is made
        // in crypto); null otherwise, so no exchange-rate row renders.
        String exchangeRateInfo = null;
        if (deposit.getPriceAmount() != null && deposit.getPriceAmount().compareTo(BigDecimal.ZERO) != 0
                && deposit.getPriceCurrency() != null && !"usd".equalsIgnoreCase(deposit.getPriceCurrency())) {
            BigDecimal impliedRate = deposit.getAmount()
                    .divide(deposit.getPriceAmount(), 6, RoundingMode.HALF_UP);
            exchangeRateInfo = "1 " + deposit.getPriceCurrency().toUpperCase() + " = " + impliedRate.toPlainString() + " USD";
        }

        emailService.sendDepositConfirmation(wallet.getAccountNumber(), deposit.getAmount().toPlainString(),
                deposit.getCurrency().name(), deposit.getReference(), wallet.getBalance().toPlainString(),
                new EmailService.DepositDetails("NOWPayments", exchangeRateInfo, null,
                        null, paymentId, recipientName, wallet.getAccountNumber(), wallet.getId()));

        log.info("Wallet credited via NOWPayments: orderId={} amount={} paymentId={}", orderId, deposit.getAmount(), paymentId);
    }

    @Transactional
    public void markNowPaymentsTransactionFailed(String orderId, String reason) {
        depositRepository.findByReference(orderId).ifPresentOrElse(deposit -> {
            if (deposit.getStatus() == DepositStatus.SUCCESS) {
                log.warn("Ignoring failure callback for already-completed NOWPayments orderId={}", orderId);
                return;
            }
            deposit.setStatus(DepositStatus.FAILED);
            deposit.setFailureReason(reason);
            depositRepository.save(deposit);
            log.warn("NOWPayments deposit failed: orderId={} reason={}", orderId, reason);
        }, () -> log.warn("Failure callback for unknown NOWPayments orderId={}: {}", orderId, reason));
    }

    /**
     * Flags a payment for manual review rather than crediting or failing it
     * outright — used for payment_status="partially_paid" (customer sent
     * less than the quoted crypto amount, e.g. due to price movement during
     * the payment window). NOWPayments holds these funds; per their docs,
     * resolving this (refund vs. request the shortfall vs. accept it) is a
     * manual dashboard action, not something to silently auto-resolve.
     *
     * Deliberately leaves status at PENDING rather than introducing a
     * dedicated DepositStatus value for this — reuses failureReason to
     * carry the flag note, same repurposing the original Transaction-based
     * version did with description. Worth reconsidering if this state
     * turns out to be common enough to deserve its own status value.
     */
    @Transactional
    public void flagNowPaymentsPartialPayment(String orderId, String paymentId) {
        depositRepository.findByReference(orderId).ifPresentOrElse(deposit -> {
            if (deposit.getStatus() == DepositStatus.SUCCESS || deposit.getStatus() == DepositStatus.FAILED) {
                return;
            }
            deposit.setFailureReason("UNDERPAID (payment " + paymentId
                    + ") — needs manual review in NOWPayments dashboard, do not auto-credit");
            depositRepository.save(deposit);
            log.warn("NOWPayments partial payment flagged for manual review: orderId={} paymentId={}", orderId, paymentId);
        }, () -> log.warn("Partial payment callback for unknown NOWPayments orderId={}: paymentId={}", orderId, paymentId));
    }

    /**
     * Mirrors StripeDepositService.autoFailStuckStripeDeposits — same
     * reasoning: a customer who creates a payment (gets a deposit address)
     * and then never actually sends anything — wrong network, changed
     * their mind, walked away — triggers no webhook at all, since nothing
     * happened on NOWPayments' side to report. Without this, that
     * deposit sits PENDING forever with no terminal state.
     *
     * Safe to auto-fail here for the same reason Stripe's version is safe:
     * the wallet is never credited for a PENDING deposit in the first
     * place (see initiateNowPaymentsDeposit).
     *
     * CUTOFF DELIBERATELY LONGER than Stripe's 30 minutes — a card
     * authorization resolves in seconds; a genuine crypto payment can
     * legitimately take much longer to reach enough blockchain
     * confirmations, especially for slower networks (Bitcoin) versus
     * faster ones (TRC20/USDT, typically used in this integration).
     * 90 minutes is a starting point, not a rigorously derived number —
     * tighten or loosen based on which cryptocurrencies your users
     * actually end up using once there's real usage data.
     *
     * Now a real provider-filtered query (findByProviderAndStatusAndCreatedAtBefore)
     * instead of the previous string-prefix matching against
     * Transaction.description — Deposit has a genuine provider field.
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void autoFailStuckNowPaymentsDeposits() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(90);
        List<Deposit> stuck = depositRepository
                .findByProviderAndStatusAndCreatedAtBefore("NOWPAYMENTS", DepositStatus.PENDING, cutoff);

        for (Deposit deposit : stuck) {
            deposit.setStatus(DepositStatus.FAILED);
            deposit.setFailureReason("Abandoned — no confirmation within 90 minutes");
            depositRepository.save(deposit);
        }

        if (!stuck.isEmpty()) {
            log.warn("{} NOWPayments deposit(s) auto-failed after sitting PENDING beyond 90 minutes: {}",
                    stuck.size(), stuck.stream().map(Deposit::getId).toList());
        }
    }
}