package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Payment;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends MongoRepository<Payment, String> {
    List<Payment> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Payment> findByReference(String reference);

    /** Used by IdempotencyService to detect a duplicate payment request before the wallet is touched. */
    boolean existsByReference(String reference);
}