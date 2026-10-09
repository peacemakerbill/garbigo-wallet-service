package com.premisave.wallet.service;

import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Records the Transaction row for a confirmed deposit — mirrors
 * DisbursementTransactionRecorder exactly, on the opposite side of the
 * wallet. Kept as its own small shared service (rather than inline logic
 * duplicated in each provider's deposit service) for the same reason:
 * every provider-specific deposit service's completion handler needs it
 * independently, without creating a circular dependency on some larger
 * shared class.
 */
@Service
@RequiredArgsConstructor
public class DepositTransactionRecorder {

    private final TransactionRepository transactionRepository;

    public void record(String userId, String walletId, BigDecimal amount,
                        Deposit deposit, String reference) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(walletId);
        tx.setType(TransactionType.DEPOSIT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        // was hardcoded Currency.KES regardless of the deposit's
        // actual currency — predates the wallet-currency-to-USD
        // conversion work entirely, back when every deposit really was
        // KES and hardcoding it was correct at the time. Every deposit
        // is USD-denominated on the wallet side now (see Deposit.currency,
        // already set correctly by every provider's deposit service) —
        // this just needed to read that instead of assuming.
        tx.setCurrency(deposit.getCurrency());
        tx.setDescription("Deposit via " + deposit.getProvider());
        tx.setReference(reference);
        tx.setProviderReference(deposit.getProviderReference());
        transactionRepository.save(tx);
    }
}