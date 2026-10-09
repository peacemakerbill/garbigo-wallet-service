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
import java.util.List;

/**
 * PayPal disbursement logic — split out of DisbursementService, mirroring
 * PaypalDepositService's role on the deposit side.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaypalDisbursementService {

    private final WalletRepository walletRepository;
    private final DisbursementRepository disbursementRepository;
    private final PaypalService paypalService;
    private final FxRateService fxRateService;
    private final DisbursementTransactionRecorder transactionRecorder;
    private final CommissionService commissionService;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;

    private static final List<String> PAYPAL_TERMINAL_FAILURE_STATUSES =
            List.of("FAILED", "DENIED", "BLOCKED", "RETURNED", "REFUNDED", "REVERSED", "CANCELED");

    /**
     * Called from DisbursementService.processDisbursement via the shared
     * ProviderResult switch — that method owns Disbursement record
     * creation and PENDING-status handling, identical across Stripe/
     * PayPal/NOWPayments, so it isn't duplicated here.
     */
    public ProviderResult disbursePaypal(DisbursementRequest request, String destinationEmail) {
        try {
            BigDecimal usdToKesRate = fxRateService.getRate("USD", "KES");
            BigDecimal usdAmount = request.getAmount()
                    .divide(usdToKesRate, 2, java.math.RoundingMode.HALF_UP);
            String batchId = paypalService.processPayout(destinationEmail, usdAmount, "USD");
            log.info("PayPal payout: kesAmount={} usdAmount={} rate={} batchId={}",
                    request.getAmount(), usdAmount, usdToKesRate, batchId);
            return new ProviderResult(true, "PayPal payout initiated (USD " + usdAmount + ")", batchId);
        } catch (Exception e) {
            return new ProviderResult(false, e.getMessage(), null);
        }
    }

    @Transactional
    public void completePaypalDisbursement(String payoutBatchId, String transactionStatus,
                                            String paypalTransactionId, String errorMessage) {
        Disbursement d = disbursementRepository.findByProviderReference(payoutBatchId).orElse(null);
        if (d == null) {
            log.warn("PayPal payout webhook for unknown payout_batch_id={} — ignoring", payoutBatchId);
            return;
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            log.warn("PayPal payout webhook for already-finalized disbursement id={} status={} — ignoring duplicate",
                    d.getId(), d.getStatus());
            return;
        }

        if ("SUCCESS".equals(transactionStatus)) {
            d.setStatus(DisbursementStatus.SUCCESS);

            if (d.getWalletId() != null) {
                // First touch of the wallet for this disbursement — see
                // MpesaDisbursementService.completeMpesaDisbursement for
                // the same reasoning on the negative-balance edge case.
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));

                // Debits d.getTotalDebited() (amount + commission), NOT
                // d.getAmount() — PayPal still pays out d.getAmount()
                // unaffected, but the wallet owes the extra commission on
                // top. Falls back to d.getAmount() for a legacy
                // disbursement created before this field existed.
                BigDecimal debitAmount = d.getTotalDebited() != null ? d.getTotalDebited() : d.getAmount();
                BigDecimal newBalance = wallet.getBalance().subtract(debitAmount);
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    log.error("Wallet {} balance went negative ({}) debiting confirmed PayPal disbursement id={} — needs manual reconciliation",
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
                        new EmailService.DisbursementDetails("PayPal", null,
                                senderName, wallet.getAccountNumber(), wallet.getId()));
            } else {
                disbursementRepository.save(d);
            }

            log.info("PayPal disbursement completed: id={} payoutBatchId={} paypalTransactionId={}",
                    d.getId(), payoutBatchId, paypalTransactionId);
        } else if (PAYPAL_TERMINAL_FAILURE_STATUSES.contains(transactionStatus)) {
            // No refund needed — the wallet was never debited for a
            // PENDING PayPal payout (see disbursePaypal above).
            d.setStatus(DisbursementStatus.FAILED);
            String reason = errorMessage != null && !errorMessage.isBlank() ? errorMessage : transactionStatus;
            d.setFailureReason(reason);
            disbursementRepository.save(d);

            if (d.getWalletId() != null) {
                walletRepository.findById(d.getWalletId()).ifPresent(wallet -> {
                    String senderName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
                    emailService.sendDisbursementFailed(wallet.getAccountNumber(),
                            d.getAmount().toPlainString(), d.getCurrency(), reason, d.getDestination(),
                            new EmailService.DisbursementDetails("PayPal", null,
                                    senderName, wallet.getAccountNumber(), wallet.getId()));
                });
            }

            log.warn("PayPal disbursement failed ({}): id={} payoutBatchId={} reason={}",
                    transactionStatus, d.getId(), payoutBatchId, errorMessage);
        } else {
            log.info("PayPal disbursement id={} payoutBatchId={} in non-terminal state={} — awaiting further webhook",
                    d.getId(), payoutBatchId, transactionStatus);
        }
    }
}