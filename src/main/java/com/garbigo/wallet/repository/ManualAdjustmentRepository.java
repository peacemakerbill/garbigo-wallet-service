package com.premisave.wallet.repository;

import com.premisave.wallet.entity.ManualAdjustment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ManualAdjustmentRepository extends MongoRepository<ManualAdjustment, String> {
    List<ManualAdjustment> findByUserIdOrderByCreatedAtDesc(String userId);
    Optional<ManualAdjustment> findByReference(String reference);

    /** Used by AdminWalletController's GET /admin/wallet/adjustments when a userId filter is supplied. */
    Page<ManualAdjustment> findByUserId(String userId, Pageable pageable);
}