package com.premisave.wallet.entity;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * One saved Stripe card attached to a wallet's Stripe Customer. A wallet
 * can have several of these — DepositService.initiateStripeDeposit always
 * charges whichever one has isDefault=true; the rest just sit here until
 * the user switches default or removes them.
 *
 * Wallet.stripeDefaultPaymentMethodId/CardBrand/CardLast4 stay as a
 * denormalized cache of THIS collection's current default row, kept in
 * sync by DepositService — so DepositService.initiateStripeDeposit,
 * WalletService.mapToResponse, and WalletResponse don't need to change at
 * all; they keep reading the same three Wallet fields as before.
 */
@Data
@Document(collection = "saved_cards")
public class SavedCard {

    @Id
    private String id;

    private String walletId;
    private String userId;

    /** pm_xxx — unique across all wallets; the same card can't be saved twice. */
    @Indexed(unique = true)
    private String stripePaymentMethodId;

    /** Display-only, same purpose as Wallet.stripeCardBrand/Last4. */
    private String brand;
    private String last4;

    /**
     * Exactly one SavedCard per wallet should have this true at a time —
     * enforced by DepositService (demote-then-promote), not a DB constraint.
     */
    private boolean isDefault;

    @CreatedDate
    private LocalDateTime createdAt;
}