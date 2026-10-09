package com.premisave.wallet.service;

import com.premisave.wallet.dto.PullTransactionRecord;
import com.premisave.wallet.dto.PullTransactionResponse;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DepositStatus;
import com.premisave.wallet.repository.DepositRepository;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PullTransactionService {

    private final MpesaService mpesaService;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final DepositRepository depositRepository;
    private final DepositTransactionRecorder depositTransactionRecorder;
    private final ExchangeRateService exchangeRateService;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;

    // ─── Registration (one-time per shortcode) ──────────────────────────────

    public PullTransactionResponse register() {
        return mpesaService.registerPullTransactions();
    }

    // ─── Query + reconciliation ──────────────────────────────────────────────

    /**
     * Pulls C2B transactions for the given window (defaults applied by the
     * caller — see queryAndReconcileDefault) and reconciles each one against
     * our local Transaction records:
     *  - Already recorded (existsByProviderReference)? Skip as duplicate.
     *  - New, and billreference matches a wallet's account number (email)?
     *    Credit the wallet now — this is exactly what would have happened
     *    had the original C2B confirmation callback reached us.
     *  - New, but no matching wallet? Left unmatched — logged for manual
     *    follow-up rather than guessed at.
     *
     * Only "c2b-pay-bill-debit" / "c2b-buy-goods-debit" style customer
     * payment records make sense to credit; anything else pulled back is
     * logged but not credited.
     */
    @Transactional
    public PullTransactionResponse queryAndReconcile(LocalDateTime startDate, LocalDateTime endDate, int offsetValue) {
        PullTransactionResponse result = mpesaService.queryPullTransactions(startDate, endDate, offsetValue);

        if (!result.isSuccess()) {
            return result;
        }

        List<PullTransactionRecord> records = result.getTransactions() != null ? result.getTransactions() : List.of();
        int recovered = 0, duplicates = 0, unmatched = 0;

        for (PullTransactionRecord record : records) {
            String txId = record.getTransactionId();
            if (txId == null || txId.isBlank()) {
                continue;
            }

            if (transactionRepository.existsByProviderReference(txId)) {
                duplicates++;
                continue;
            }

            if (!isCustomerPaymentType(record.getTransactionType())) {
                log.info("Pull Transactions: skipping non-C2B-debit record transactionId={} type={}",
                        txId, record.getTransactionType());
                continue;
            }

            String email = record.getBillReference() != null ? record.getBillReference().trim().toLowerCase() : null;
            Wallet wallet = email != null ? walletRepository.findByAccountNumber(email).orElse(null) : null;

            if (wallet == null) {
                unmatched++;
                log.warn("Pull Transactions: recovered record transactionId={} billreference={} has no matching " +
                        "wallet locally — NOT credited, needs manual review", txId, record.getBillReference());
                continue;
            }

            creditRecoveredTransaction(wallet, record);
            recovered++;
            log.info("Pull Transactions: recovered and credited missed C2B transactionId={} wallet={} amount={}",
                    txId, wallet.getId(), record.getAmount());
        }

        result.setRecovered(recovered);
        result.setDuplicates(duplicates);
        result.setUnmatched(unmatched);
        return result;
    }

    /** Convenience overload — defaults to a 1-day lookback window and offset 0 (Safaricom retains max 48h). */
    public PullTransactionResponse queryAndReconcileDefault() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(1);
        return queryAndReconcile(start, end, 0);
    }

    /**
     * Best-effort handler for whatever Safaricom posts to our registered
     * CallBackURL. Safaricom's docs don't specify this payload's shape —
     * only the Query response shape is documented — so this reuses the same
     * parser and reconciliation logic on the assumption the shape matches.
     * Logs the raw payload regardless so nothing is silently lost if the
     * assumption turns out wrong.
     */
    @Transactional
    public void handleCallback(String rawPayload) {
        log.info("Pull Transactions callback received (raw): {}", rawPayload);
        try {
            PullTransactionResponse parsed = mpesaService.parsePullTransactionsResponse(rawPayload);
            List<PullTransactionRecord> records = parsed.getTransactions() != null ? parsed.getTransactions() : List.of();

            int recovered = 0, duplicates = 0, unmatched = 0;
            for (PullTransactionRecord record : records) {
                String txId = record.getTransactionId();
                if (txId == null || txId.isBlank() || transactionRepository.existsByProviderReference(txId)) {
                    duplicates++;
                    continue;
                }
                if (!isCustomerPaymentType(record.getTransactionType())) {
                    continue;
                }
                String email = record.getBillReference() != null ? record.getBillReference().trim().toLowerCase() : null;
                Wallet wallet = email != null ? walletRepository.findByAccountNumber(email).orElse(null) : null;
                if (wallet == null) {
                    unmatched++;
                    continue;
                }
                creditRecoveredTransaction(wallet, record);
                recovered++;
            }
            log.info("Pull Transactions callback reconciled: recovered={} duplicates={} unmatched={}",
                    recovered, duplicates, unmatched);
        } catch (Exception e) {
            log.error("Failed to parse/reconcile Pull Transactions callback payload — raw payload logged above", e);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private boolean isCustomerPaymentType(String transactionType) {
        if (transactionType == null) return false;
        String t = transactionType.toLowerCase();
        return t.contains("pay-bill-debit") || t.contains("buy-goods-debit") || t.contains("c2b");
    }

    private void creditRecoveredTransaction(Wallet wallet, PullTransactionRecord record) {
        BigDecimal kesAmount = new BigDecimal(record.getAmount());

        BigDecimal rate = exchangeRateService.getRate("KES", "USD");
        BigDecimal usdAmount = kesAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);

        wallet.setBalance(wallet.getBalance().add(usdAmount));
        walletRepository.save(wallet);

        String recipientName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());

        Deposit deposit = new Deposit();
        deposit.setUserId(wallet.getUserId());
        deposit.setWalletId(wallet.getId());
        deposit.setAmount(usdAmount);
        deposit.setCurrency(Currency.USD);
        deposit.setPriceAmount(kesAmount);
        deposit.setPriceCurrency("kes");
        deposit.setProvider("MPESA");
        deposit.setChannel("MPESA_C2B_PULL_RECOVERY");
        deposit.setSource(record.getMsisdn());
        deposit.setRecipientName(recipientName);
        deposit.setStatus(DepositStatus.SUCCESS);
        deposit.setReference(record.getTransactionId());
        deposit.setProviderReference(record.getTransactionId());
        depositRepository.save(deposit);

        depositTransactionRecorder.record(wallet.getUserId(), wallet.getId(), usdAmount, deposit,
                record.getTransactionId());

        String exchangeRateInfo = "1 KES = " + rate.toPlainString() + " USD";
        emailService.sendDepositConfirmation(wallet.getAccountNumber(), usdAmount.toPlainString(),
                deposit.getCurrency().name(), deposit.getReference(), wallet.getBalance().toPlainString(),
                new EmailService.DepositDetails("M-Pesa", exchangeRateInfo, record.getMsisdn(),
                        null, record.getTransactionId(), recipientName, wallet.getAccountNumber(), wallet.getId()));

        log.info("Pull Transactions recovery credited: transactionId={} wallet={} kesAmount={} usdAmount={} rate={}",
                record.getTransactionId(), wallet.getId(), kesAmount, usdAmount, rate);
    }
}