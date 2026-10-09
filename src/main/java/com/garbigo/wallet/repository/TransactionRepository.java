package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends MongoRepository<Transaction, String> {

    List<Transaction> findByUserIdOrderByCreatedAtDesc(String userId);

    /** Used by IdempotencyService to detect duplicate references. */
    boolean existsByReference(String reference);

    /**
     * Used by the M-Pesa STK callback handler to find the PENDING transaction
     * that was created when the STK push was initiated (reference = CheckoutRequestID),
     * so the callback can be matched back to the correct wallet/user without
     * relying on the callback carrying an account number (it doesn't).
     */
    Optional<Transaction> findByReference(String reference);

    /**
     * Used by MpesaC2BService to detect duplicate M-Pesa TransIDs.
     * providerReference stores the M-Pesa TransID (e.g. "RCA71X5MJ4").
     */
    boolean existsByProviderReference(String providerReference);

    /**
     * Used by MpesaOperationsService to link a Reversal request back to the
     * original completed deposit transaction, by its M-Pesa receipt number
     * (stored as providerReference on C2B/STK deposits), so the wallet can
     * be auto-debited once the reversal succeeds.
     */
    Optional<Transaction> findByProviderReference(String providerReference);

    /**
     * Used by DepositService.autoFailStuckStripeDeposits to find deposits
     * that never resolved. No field on Transaction currently distinguishes
     * which provider a deposit used (unlike Disbursement, which has both
     * provider and channel) — the sweeper filters this broad type/status/
     * age result in-memory on description text instead.
     */
    List<Transaction> findByTypeAndStatusAndCreatedAtBefore(
            TransactionType type, TransactionStatus status, LocalDateTime cutoff);
}