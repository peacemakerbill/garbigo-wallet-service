package com.premisave.wallet.service;

import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Transfer;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Records BOTH Transaction rows (debit + credit) for a confirmed transfer
 * — mirrors DepositTransactionRecorder/DisbursementTransactionRecorder in
 * spirit, but genuinely different in shape: a transfer touches two
 * wallets in one operation, so it needs two Transaction rows per call,
 * not one.
 *
 * IMPORTANT: the debit Transaction uses transfer.getTotalDebited(), NOT
 * transfer.getAmount() — since commission is charged on top (see
 * CommissionService), what actually left the sender's wallet is
 * amount + commission, and the Transaction row has to mathematically
 * match the real balance change or the audit trail becomes internally
 * inconsistent. The credit Transaction correctly uses the plain amount,
 * since the recipient is unaffected by the commission entirely.
 */
@Service
@RequiredArgsConstructor
public class TransferTransactionRecorder {

    private final TransactionRepository transactionRepository;

    public void record(Transfer transfer, Wallet sender, Wallet recipient) {
        String descriptionSuffix = transfer.getDescription() != null && !transfer.getDescription().isBlank()
                ? " - " + transfer.getDescription() : "";

        Transaction debit = new Transaction();
        debit.setUserId(transfer.getSenderId());
        debit.setWalletId(sender.getId());
        debit.setType(TransactionType.TRANSFER);
        debit.setStatus(TransactionStatus.COMPLETED);
        debit.setAmount(transfer.getTotalDebited().negate());
        debit.setCurrency(Currency.USD);
        debit.setDescription("Transfer to " + recipient.getAccountNumber() + descriptionSuffix + " (incl. commission)");
        debit.setReference(transfer.getReference());
        transactionRepository.save(debit);

        Transaction credit = new Transaction();
        credit.setUserId(transfer.getRecipientId());
        credit.setWalletId(recipient.getId());
        credit.setType(TransactionType.TRANSFER);
        credit.setStatus(TransactionStatus.COMPLETED);
        credit.setAmount(transfer.getAmount());
        credit.setCurrency(Currency.USD);
        credit.setDescription("Transfer from " + sender.getAccountNumber() + descriptionSuffix);
        credit.setReference(transfer.getReference());
        transactionRepository.save(credit);
    }
}