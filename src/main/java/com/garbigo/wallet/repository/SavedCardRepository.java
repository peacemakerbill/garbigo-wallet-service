package com.premisave.wallet.repository;

import com.premisave.wallet.entity.SavedCard;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SavedCardRepository extends MongoRepository<SavedCard, String> {

    /** Used for GET /wallet/stripe/cards — newest first. */
    List<SavedCard> findByWalletIdOrderByCreatedAtDesc(String walletId);

    /**
     * Used by DepositService.upsertSavedCardAsDefault to find-or-create the
     * row for a given PaymentMethod (a card can be "first seen" via the
     * setup-intent confirm path, the setup_intent.succeeded webhook, or a
     * first-time deposit that saves a new card as a side effect — all three
     * need to land on the same row, not create duplicates), and by
     * removeSavedCard/setDefaultSavedCard to scope a paymentMethodId to the
     * caller's own wallet before acting on it.
     */
    Optional<SavedCard> findByStripePaymentMethodId(String stripePaymentMethodId);
}