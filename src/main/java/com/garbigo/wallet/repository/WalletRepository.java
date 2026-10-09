package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Wallet;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface WalletRepository extends MongoRepository<Wallet, String> {
    Optional<Wallet> findByAccountNumber(String accountNumber);
    Optional<Wallet> findByUserId(String userId);
    Optional<Wallet> findByStripeCustomerId(String stripeCustomerId);

    /**
     * Resolves which wallet an "account.updated" Stripe Connect webhook
     * belongs to, so payouts_enabled / linked bank display fields can be
     * kept in sync — see WalletService.updateStripeConnectAccountStatus.
     */
    Optional<Wallet> findByStripeConnectedAccountId(String stripeConnectedAccountId);

    /**
     * Resolves which wallet a VAULT.PAYMENT-TOKEN.CREATED webhook belongs
     * to, when PayPal finalizes vaulting asynchronously after a capture
     * that returned vault.status="APPROVED" rather than "VAULTED" — see
     * DepositService.attachPaypalVaultToken.
     */
    Optional<Wallet> findByPaypalCustomerId(String paypalCustomerId);

    /**
     * Used by WalletService.updateMpesaPhoneNumber to enforce uniqueness
     * before saving, and by MpesaC2BService as the primary account lookup
     * for C2B deposits — the M-Pesa "Account Number" a customer types on
     * Pay Bill is now this number, not the wallet's email.
     * Normalized to 254XXXXXXXXX before querying/saving (see
     * MpesaService.normalizePhone).
     */
    Optional<Wallet> findByMpesaPhoneNumber(String mpesaPhoneNumber);
}