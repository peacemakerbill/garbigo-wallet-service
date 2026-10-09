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
 * Stripe Connect disbursement logic — split out of DisbursementService,
 * mirroring StripeDepositService's role on the deposit side.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeDisbursementService {

    private final WalletRepository walletRepository;
    private final DisbursementRepository disbursementRepository;
    private final StripeService stripeService;
    private final FxRateService fxRateService;
    private final DisbursementTransactionRecorder transactionRecorder;
    private final CommissionService commissionService;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;

    /**
     * Converts the wallet's KES amount to whatever currency the withdrawal
     * is actually denominated in (defaults to USD — see DisbursementRequest.
     * currency javadoc), then kicks off the Connect Transfer+Payout. Mirrors
     * PaypalDisbursementService.disbursePaypal's FX pattern — an earlier
     * version of this method skipped FX conversion entirely and passed the
     * raw KES amount straight through as if it were already USD, which
     * would have sent the wrong amount of money to a real bank account.
     *
     * Called from DisbursementService.processDisbursement via the shared
     * ProviderResult switch — that method owns Disbursement record
     * creation and PENDING-status handling, identical across Stripe/
     * PayPal/NOWPayments, so it isn't duplicated here.
     */
    public ProviderResult disburseStripe(DisbursementRequest request, String connectedAccountId, String idempotencyKey) {
        try {
            String currency = request.getCurrency() != null ? request.getCurrency().toUpperCase() : "USD";

            BigDecimal payoutAmount = request.getAmount();
            if (!"KES".equals(currency)) {
                BigDecimal kesToTargetRate = fxRateService.getRate("KES", currency);
                payoutAmount = request.getAmount().multiply(kesToTargetRate).setScale(2, java.math.RoundingMode.HALF_UP);
            }

            StripeService.ConnectPayoutResult result = stripeService.transferAndPayout(
                    connectedAccountId, payoutAmount, currency, idempotencyKey);

            if (!result.success()) {
                return new ProviderResult(false, result.message(), null);
            }

            log.info("Stripe Connect payout: accountId={} kesAmount={} {}Amount={} payoutId={}",
                    connectedAccountId, request.getAmount(), currency, payoutAmount, result.payoutId());
            return new ProviderResult(true, "Stripe payout initiated (" + currency + " " + payoutAmount + ")",
                    result.payoutId());
        } catch (Exception e) {
            return new ProviderResult(false, e.getMessage(), null);
        }
    }

    /**
     * Keyed by the Stripe Payout id (po_xxx), stored as providerReference
     * at initiation — see StripeService.transferAndPayout, which returns
     * that as the primary reference (not the Transfer id) since that's
     * what payout.paid/payout.failed events carry.
     *
     * IMPORTANT DIFFERENCE from every other provider's failure path here:
     * a failed Stripe Connect payout means money has ALREADY left
     * Premisave's own platform balance via the earlier Transfer step and
     * is sitting in the connected account's own Stripe balance — it is
     * NOT the "nothing moved, nothing to refund" situation that MPESA/
     * PAYPAL/FLUTTERWAVE/NOWPAYMENTS failures are. This is logged at ERROR
     * (not WARN) specifically so it doesn't blend into routine failure
     * noise; someone needs to either retry a Payout on that connected
     * account (the funds are already there) or treat it as an operational
     * loss.
     */
    @Transactional
    public void completeStripeConnectDisbursement(String payoutId, boolean success, String failureReason) {
        Disbursement d = disbursementRepository.findByProviderReference(payoutId).orElse(null);
        if (d == null) {
            log.warn("Stripe Connect payout webhook for unknown payoutId={} — ignoring", payoutId);
            return;
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            log.warn("Stripe Connect payout webhook for already-finalized disbursement id={} status={} — ignoring duplicate",
                    d.getId(), d.getStatus());
            return;
        }

        if (success) {
            d.setStatus(DisbursementStatus.SUCCESS);

            if (d.getWalletId() != null) {
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));

                // Debits d.getTotalDebited() (amount + commission), NOT
                // d.getAmount() — Stripe still pays out d.getAmount()
                // unaffected, but the wallet owes the extra commission on
                // top. Falls back to d.getAmount() for a legacy
                // disbursement created before this field existed.
                BigDecimal debitAmount = d.getTotalDebited() != null ? d.getTotalDebited() : d.getAmount();
                BigDecimal newBalance = wallet.getBalance().subtract(debitAmount);
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    log.error("Wallet {} balance went negative ({}) debiting confirmed Stripe Connect disbursement id={} — needs manual reconciliation",
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
                        new EmailService.DisbursementDetails("Stripe", null,
                                senderName, wallet.getAccountNumber(), wallet.getId()));
            } else {
                disbursementRepository.save(d);
            }

            log.info("Stripe Connect disbursement completed: id={} payoutId={}", d.getId(), payoutId);
        } else {
            d.setStatus(DisbursementStatus.FAILED);
            d.setFailureReason(failureReason);
            disbursementRepository.save(d);

            if (d.getWalletId() != null) {
                walletRepository.findById(d.getWalletId()).ifPresent(wallet -> {
                    String senderName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
                    emailService.sendDisbursementFailed(wallet.getAccountNumber(),
                            d.getAmount().toPlainString(), d.getCurrency(), failureReason, d.getDestination(),
                            new EmailService.DisbursementDetails("Stripe", null,
                                    senderName, wallet.getAccountNumber(), wallet.getId()));
                });
            }

            log.error("Stripe Connect payout FAILED: id={} payoutId={} reason={} destinationAccount={} — " +
                    "funds already left the platform balance via the earlier Transfer and are stranded in " +
                    "that connected account; needs manual reconciliation, NOT a routine no-op failure",
                    d.getId(), payoutId, failureReason, d.getDestination());
        }
    }
}