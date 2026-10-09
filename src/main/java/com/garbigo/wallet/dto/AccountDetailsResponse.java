package com.premisave.wallet.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * One row of GET /internal/accounts — the wallet-owner directory another
 * Premisave service (e.g. property-service) can look up by, resolving
 * name/phone/payment-method details without needing a separate call to
 * auth-service for everything except the name itself.
 *
 * Deliberately a SEPARATE DTO from WalletResponse rather than adding a
 * fullName field there — WalletResponse is returned by many existing
 * endpoints (getWallet, createWallet, freezeWallet, the various
 * updateX/disconnectX methods) that never resolve a name, so adding a
 * field there would leave it silently null everywhere except this one
 * new endpoint, which is confusing rather than useful. This DTO's
 * contract is fully explicit: every field here IS meant to be populated,
 * for this endpoint's own purpose.
 *
 * Balance is deliberately NOT included — the request that led to this
 * endpoint asked for contact/payment-method details specifically (names,
 * phone numbers, PayPal email, etc.), not financial data; add it
 * explicitly later if a real need for it shows up.
 */
@Data
public class AccountDetailsResponse {

    private String userId;

    /** The user's email — same value as Wallet.accountNumber. */
    private String accountNumber;

    /**
     * Resolved via UserNameResolver (a thin wrapper over auth-service's
     * cross-service lookup) — null if that lookup failed, or the
     * account genuinely has no name on file. Same lookup mechanism
     * already used throughout deposit/disbursement/transfer/payment
     * confirmation emails this session — see UserNameResolver's own
     * javadoc for why it deliberately exposes only the name, never
     * active/verified/role.
     */
    private String fullName;

    private boolean frozen;

    private String mpesaPhoneNumber;
    private String pochiPhoneNumber;

    /** Manually-typed, unverified PayPal payout destination — see Wallet.paypalEmail's own javadoc for why this is distinct from paypalConnectedEmail below. */
    private String paypalEmail;

    /** PayPal-confirmed email from Vault linking — distinct from paypalEmail above. Null if no PayPal account is linked. */
    private String paypalConnectedEmail;

    /** Whether a Stripe Connect account (international bank withdrawals) is linked and currently able to receive payouts. */
    private boolean stripeConnected;
    private boolean stripePayoutsEnabled;

    /** Display-only network code (e.g. "MTN", "AIRTEL") for the saved Flutterwave payment method — null if none linked. */
    private String flutterwavePaymentMethodNetwork;
    /** Display-only phone number for the saved Flutterwave payment method — null if none linked. */
    private String flutterwavePaymentMethodPhone;

    private LocalDateTime createdAt;
}