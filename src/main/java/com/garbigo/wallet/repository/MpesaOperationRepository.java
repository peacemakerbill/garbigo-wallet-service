package com.premisave.wallet.repository;

import com.premisave.wallet.entity.MpesaOperation;
import com.premisave.wallet.enums.DisbursementStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MpesaOperationRepository extends MongoRepository<MpesaOperation, String> {

    /** Used to reconcile async ResultURL/timeout callbacks back to the operation that started them. */
    Optional<MpesaOperation> findByConversationId(String conversationId);

    /** Used by the stuck-operation sweeper, same pattern as DisbursementRepository. */
    List<MpesaOperation> findByStatusAndCreatedAtBefore(DisbursementStatus status, LocalDateTime cutoff);
}