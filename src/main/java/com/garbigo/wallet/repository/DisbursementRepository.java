package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.enums.DisbursementStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DisbursementRepository extends MongoRepository<Disbursement, String> {
    List<Disbursement> findByUserIdOrderByCreatedAtDesc(String userId);

    /** Used to reconcile async B2C/B2B result callbacks back to the disbursement that started them. */
    Optional<Disbursement> findByProviderReference(String providerReference);

    /** Used by the stuck-disbursement sweeper. */
    List<Disbursement> findByStatusAndCreatedAtBefore(DisbursementStatus status, LocalDateTime cutoff);

    /**
     * Used by IdempotencyService to detect a duplicate disbursement request.
     * Necessary because a Disbursement record is created immediately (status
     * PENDING/SUCCESS/FAILED) while the corresponding Transaction row is only
     * created later, once success is actually confirmed (synchronously for
     * Stripe, or via webhook for M-Pesa/PayPal) — checking TransactionRepository
     * alone lets a resubmitted reference slip through and trigger a second
     * real payout while the first is still PENDING.
     */
    boolean existsByReference(String reference);
}