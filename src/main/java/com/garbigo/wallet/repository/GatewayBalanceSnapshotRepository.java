package com.premisave.wallet.repository;

import com.premisave.wallet.entity.GatewayBalanceSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface GatewayBalanceSnapshotRepository extends MongoRepository<GatewayBalanceSnapshot, String> {

    /** Powers "latest balance per provider" — one call per provider, five total for the full saved-balances view. */
    Optional<GatewayBalanceSnapshot> findFirstByProviderOrderByCreatedAtDesc(String provider);

    /** Powers the full paginated history view for one provider. */
    Page<GatewayBalanceSnapshot> findByProviderOrderByCreatedAtDesc(String provider, Pageable pageable);
}