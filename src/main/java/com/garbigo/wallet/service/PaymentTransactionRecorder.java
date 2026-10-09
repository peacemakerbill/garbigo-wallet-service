package com.garbigo.wallet.service;

import com.garbigo.wallet.entity.Payment;
import com.garbigo.wallet.entity.Transaction;
import com.garbigo.wallet.enums.Currency;
import com.garbigo.wallet.enums.TransactionStatus;
import com.garbigo.wallet.enums.TransactionType;
import com.garbigo.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Records the Transaction row for a confirmed payment — mirrors
 * DepositTransactionRecorder/DisbursementTransactionRecorder exactly.
 */
@Service
@RequiredArgsConstructor
public class PaymentTransactionRecorder {

    private final TransactionRepository transactionRepository;

    public void record(Payment payment) {
        Transaction tx = new Transaction();
        tx.setUserId(payment.getUserId());
        tx.setWalletId(payment.getWalletId());
        tx.setType(TransactionType.PAYMENT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(payment.getAmount());
        tx.setCurrency(Currency.USD);
        tx.setDescription("Payment to " + payment.getService()
                + (payment.getDescription() != null && !payment.getDescription().isBlank() ? " - " + payment.getDescription() : ""));
        tx.setReference(payment.getReference());
        transactionRepository.save(tx);
    }
}