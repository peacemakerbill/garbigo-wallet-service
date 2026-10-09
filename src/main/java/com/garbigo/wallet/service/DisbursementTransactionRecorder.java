package com.premisave.wallet.service;

import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Records the Transaction row for a confirmed disbursement — extracted
 * out of DisbursementService (where it was a private helper,
 * saveDisbursementTransaction) specifically so every provider-specific
 * disbursement service (MpesaDisbursementService,
 * StripeDisbursementService, PaypalDisbursementService,
 * FlutterwaveDisbursementService, NowPaymentsDisbursementService) can call
 * it independently from their own completeXDisbursement webhook handlers,
 * without each needing to depend on DisbursementService itself — same
 * circular-dependency reasoning as ProviderResult's javadoc.
 */
@Service
@RequiredArgsConstructor
public class DisbursementTransactionRecorder {

    private final TransactionRepository transactionRepository;

    public void record(String userId, String walletId, BigDecimal amount,
                        Disbursement disbursement, String reference) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(walletId);
        tx.setType(TransactionType.DISBURSEMENT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setCurrency(Currency.USD);
        tx.setDescription("Disbursement via " + disbursement.getProvider() + " to " + disbursement.getDestination());
        tx.setReference(reference);
        tx.setProviderReference(disbursement.getProviderReference());
        transactionRepository.save(tx);
    }
}