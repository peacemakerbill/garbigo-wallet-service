package com.premisave.wallet.service;

import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * NOWPayments disbursement logic — split out of DisbursementService,
 * mirroring NowPaymentsDepositService's role on the deposit side.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NowPaymentsDisbursementService {

    private final WalletRepository walletRepository;
    private final DisbursementRepository disbursementRepository;
    private final NowPaymentsService nowPaymentsService;
    private final DisbursementTransactionRecorder transactionRecorder;
    private final CommissionService commissionService;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;

    /**
     * Converts the wallet's KES amount into the target crypto using
     * NOWPayments' own /v1/estimate rates (see NowPaymentsService.
     * getEstimatedAmount's javadoc for why FxRateService can't do this),
     * then creates the payout. Returns providerRef = the NOWPayments
     * payout id — same role as Stripe's payoutId / PayPal's batchId — but
     * note this payout does NOT execute yet even on success here; it sits
     * awaiting 2FA verification (see verifyNowPaymentsDisbursement below).
     *
     * Called from DisbursementService.processDisbursement via the shared
     * ProviderResult switch — that method owns Disbursement record
     * creation and PENDING-status handling, identical across Stripe/
     * PayPal/NOWPayments, so it isn't duplicated here.
     */
    public ProviderResult disburseNowPayments(DisbursementRequest request, String address, String idempotencyKey) {
        try {
            NowPaymentsService.EstimateResult estimate = nowPaymentsService.getEstimatedAmount(
                    request.getAmount(), "kes", request.getNowPaymentsCurrency());

            if (!estimate.success()) {
                return new ProviderResult(false, estimate.message(), null);
            }

            NowPaymentsService.CreatePayoutResult result = nowPaymentsService.createPayout(
                    address, request.getNowPaymentsCurrency(), estimate.estimatedAmount(), idempotencyKey);

            if (!result.success()) {
                return new ProviderResult(false, result.message(), null);
            }

            log.info("NOWPayments payout: address={} kesAmount={} {}Amount={} payoutId={}",
                    address, request.getAmount(), request.getNowPaymentsCurrency(),
                    estimate.estimatedAmount(), result.payoutId());
            return new ProviderResult(true,
                    "NOWPayments payout created (" + request.getNowPaymentsCurrency().toUpperCase()
                            + " " + estimate.estimatedAmount() + ") — awaiting 2FA verification",
                    result.payoutId());
        } catch (Exception e) {
            return new ProviderResult(false, e.getMessage(), null);
        }
    }

    /**
     * Keyed by the NOWPayments payout id, stored as providerReference at
     * initiation — see disburseNowPayments above. Statuses observed
     * inconsistently across NOWPayments' own documentation (some sources
     * list FINISHED/FAILED/REJECTED, others waiting/processing/sending/
     * finished/failed) — PaymentCallbackController.nowPaymentsWebhook
     * normalizes whatever it receives to a simple success/failure boolean
     * before calling this, so this method itself doesn't need to know the
     * exact status vocabulary.
     *
     * Same reasoning as every other provider here: the wallet is never
     * debited until this fires, so a failure is a clean no-op, not a
     * stranded-funds situation — unlike Stripe Connect, NOWPayments'
     * create+verify flow doesn't have an equivalent "money already left an
     * intermediate balance" step before this final confirmation.
     */
    @Transactional
    public void completeNowPaymentsDisbursement(String payoutId, boolean success, String failureReason) {
        Disbursement d = disbursementRepository.findByProviderReference(payoutId).orElse(null);
        if (d == null) {
            log.warn("NOWPayments payout webhook for unknown payoutId={} — ignoring", payoutId);
            return;
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            log.warn("NOWPayments payout webhook for already-finalized disbursement id={} status={} — ignoring duplicate",
                    d.getId(), d.getStatus());
            return;
        }

        if (success) {
            d.setStatus(DisbursementStatus.SUCCESS);

            if (d.getWalletId() != null) {
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));

                // Debits d.getTotalDebited() (amount + commission), NOT
                // d.getAmount() — NOWPayments still sends the crypto
                // equivalent of d.getAmount() unaffected, but the wallet
                // owes the extra commission on top. Falls back to
                // d.getAmount() for a legacy disbursement created before
                // this field existed.
                BigDecimal debitAmount = d.getTotalDebited() != null ? d.getTotalDebited() : d.getAmount();
                BigDecimal newBalance = wallet.getBalance().subtract(debitAmount);
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    log.error("Wallet {} balance went negative ({}) debiting confirmed NOWPayments disbursement id={} — needs manual reconciliation",
                            wallet.getId(), newBalance, d.getId());
                }
                wallet.setBalance(newBalance);
                walletRepository.save(wallet);

                disbursementRepository.save(d);
                transactionRecorder.record(d.getUserId(), d.getWalletId(), debitAmount, d, d.getReference());
                commissionService.recordGatewayCommissionFromDisbursement(d);

                String senderName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
                d.setSenderName(senderName);
                disbursementRepository.save(d);
                emailService.sendDisbursementSuccess(wallet.getAccountNumber(), d.getAmount().toPlainString(),
                        d.getCurrency(), d.getDestination(), d.getReference(),
                        new EmailService.DisbursementDetails("NOWPayments", null,
                                senderName, wallet.getAccountNumber(), wallet.getId()));
            } else {
                disbursementRepository.save(d);
            }

            log.info("NOWPayments disbursement completed: id={} payoutId={}", d.getId(), payoutId);
        } else {
            // No refund needed — the wallet was never debited for a
            // PENDING NOWPayments disbursement (see disburseNowPayments above).
            d.setStatus(DisbursementStatus.FAILED);
            d.setFailureReason(failureReason);
            disbursementRepository.save(d);

            if (d.getWalletId() != null) {
                walletRepository.findById(d.getWalletId()).ifPresent(wallet -> {
                    String senderName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
                    emailService.sendDisbursementFailed(wallet.getAccountNumber(),
                            d.getAmount().toPlainString(), d.getCurrency(), failureReason, d.getDestination(),
                            new EmailService.DisbursementDetails("NOWPayments", null,
                                    senderName, wallet.getAccountNumber(), wallet.getId()));
                });
            }

            log.warn("NOWPayments disbursement failed: id={} payoutId={} reason={}", d.getId(), payoutId, failureReason);
        }
    }

    /**
     * Verifies a NOWPayments disbursement with its 2FA code — the step
     * that actually makes NOWPayments start processing the payout (see
     * NowPaymentsService.verifyPayout's javadoc; per NOWPayments' own
     * support docs, an unverified payout auto-rejects after ~1 hour).
     *
     * WHERE THE CODE COMES FROM depends entirely on how 2FA is configured
     * on your NOWPayments account:
     *  - App-based (TOTP, "Use an app" in Dashboard → Account settings →
     *    Two step authentication) — this CAN be fully automated. If you
     *    hold the TOTP secret server-side, generate the current code
     *    yourself (e.g. via a small RFC 6238 implementation or a library
     *    like `com.warrenstrange:googleauth`) and call this method
     *    immediately after disburseNowPayments succeeds, with no human in
     *    the loop — same fully-automated shape as every other provider's
     *    withdrawal flow in this codebase. Not implemented here since it
     *    depends on a secret this class has no access to; wire it in once
     *    you've confirmed this is how 2FA is actually configured.
     *  - Email-based — a human has to open an email and read the code out.
     *    This genuinely cannot be automated by this backend; this method
     *    stays exposed behind DisbursementController's
     *    /disbursements/nowpayments/{id}/verify endpoint, called manually
     *    once the code is in hand.
     *  - 2FA disabled entirely — NOWPayments creates the payout already
     *    fully processing (per their own docs), and calling this at all
     *    would simply fail since there's nothing left to verify. Check
     *    d.getStatus() / a fresh getPayoutStatus() call before assuming
     *    this step is even necessary if you go this route (not
     *    recommended for a real business — see the earlier discussion of
     *    what disabling this control actually trades away).
     *
     * Ownership-checked the same way withdrawal-adjacent actions are
     * checked elsewhere in this codebase — callerUserId must match the
     * disbursement's own userId, so one user can't verify (and thereby
     * trigger) a payout that isn't theirs.
     */
    @Transactional
    public void verifyNowPaymentsDisbursement(String disbursementId, String verificationCode, String callerUserId) {
        Disbursement d = disbursementRepository.findById(disbursementId)
                .orElseThrow(() -> new IllegalArgumentException("Disbursement not found: " + disbursementId));

        if (!callerUserId.equals(d.getUserId())) {
            throw new IllegalArgumentException("This disbursement does not belong to the authenticated user");
        }

        if (!"NOWPAYMENTS".equals(d.getProvider())) {
            throw new IllegalArgumentException("Disbursement " + disbursementId + " is not a NOWPayments disbursement");
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            throw new IllegalStateException(
                    "This disbursement is already " + d.getStatus() + " — nothing left to verify.");
        }

        boolean verified = nowPaymentsService.verifyPayout(d.getProviderReference(), verificationCode);
        if (!verified) {
            throw new IllegalStateException(
                    "NOWPayments rejected the verification code — check it's correct and hasn't expired.");
        }

        log.info("NOWPayments disbursement verified: id={} payoutId={} — now processing, awaiting webhook confirmation",
                d.getId(), d.getProviderReference());
        // Deliberately NOT changing d.getStatus() here — it stays PENDING
        // until completeNowPaymentsDisbursement resolves it via the
        // webhook, same as every other provider's confirmation step.
    }
}