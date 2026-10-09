package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Transfer;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TransferRepository extends MongoRepository<Transfer, String> {
    List<Transfer> findBySenderIdOrderByCreatedAtDesc(String senderId);
    List<Transfer> findByRecipientIdOrderByCreatedAtDesc(String recipientId);

    /**
     * Combined sent+received history for a "my transfers" view — the same
     * userId is passed for both parameters. A single MongoDB query,
     * correctly sorted, rather than fetching sender/recipient lists
     * separately and merge-sorting them in application code.
     */
    List<Transfer> findBySenderIdOrRecipientIdOrderByCreatedAtDesc(String senderId, String recipientId);

    /** Used to reconcile a transfer back from its own idempotency reference. */
    Optional<Transfer> findByReference(String reference);

    /** Used by IdempotencyService to detect a duplicate transfer request before any wallet is touched. */
    boolean existsByReference(String reference);
}