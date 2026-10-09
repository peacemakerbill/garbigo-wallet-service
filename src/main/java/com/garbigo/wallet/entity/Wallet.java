package com.premisave.wallet.entity;

import com.premisave.wallet.enums.Currency;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Document(collection = "wallets")
public class Wallet {

    @Id
    private String id;

    @Indexed(unique = true)
    private String accountNumber; // User's email

    private String userId;

    private BigDecimal balance = BigDecimal.ZERO;

    /**
     * Fixed at USD for every wallet — deliberately NOT user-changeable
     * (no setter is exposed anywhere for this by design). M-Pesa (KES-
     * native) and Flutterwave mobile money (local-currency-native) both
     * convert to USD before touching balance — see
     * MpesaDepositService/MpesaDisbursementService and
     * FlutterwaveDepositService/FlutterwaveDisbursementService. PayPal
     * and Stripe are already USD-native and need no conversion at all.
     */
    private Currency currency = Currency.USD;

    private boolean isFrozen = false;

    /**
     * PayPal email the user wants payouts sent to. Resolved authoritatively
     * from here for PayPal disbursements (see DisbursementService) — never
     * taken from the disbursement request itself, same reasoning as M-Pesa's
     * verified-phone-number pattern: eliminates typo/mistargeted-payout risk.
     * Set via PUT /wallet/paypal-email.
     *
     * NOTE: this is a manually-typed, unverified destination — distinct from
     * paypalConnectedEmail below, which PayPal itself confirms via Vault.
     */
    private String paypalEmail;

    /**
     * M-Pesa phone number the user wants deposits (quick STK push reloads)
     * and disbursements sent to. Resolved authoritatively from here — never
     * taken from a deposit/disbursement request itself — same reasoning as
     * paypalEmail: eliminates typo/mistargeted-payout risk and lets the user
     * pick which verified number to use.
     *
     * SECURITY: for B2C/B2Pochi withdrawals, this being unset is a hard
     * rejection — DisbursementService.resolveVerifiedPhoneNumber requires it
     * to be present and throws PhoneNumberUnavailableException otherwise.
     * There is intentionally no fallback to the auth-service profile's phone
     * number; only a number explicitly attached to this wallet via PUT
     * /wallet/mpesa-phone is ever used as a payout destination. Deposits
     * require the caller to supply a phoneNumber explicitly instead.
     *
     * Unique across wallets (sparse — many wallets may still have this
     * unset) — this is now also the account reference customers type into
     * the M-Pesa Pay Bill "Account Number" field for C2B deposits (see
     * MpesaC2BService.validateAccount/processConfirmation), so two wallets
     * sharing a number would misdirect deposits.
     * Stored normalized to 254XXXXXXXXX. Set via PUT /wallet/mpesa-phone.
     */
    @Indexed(unique = true, sparse = true)
    private String mpesaPhoneNumber;

    /**
     * Phone number the user's Pochi la Biashara business account is
     * registered under — used specifically for B2Pochi withdrawals (see
     * DisbursementService.resolveVerifiedPochiPhoneNumber). Distinct from
     * mpesaPhoneNumber above: a user's Pochi account can be registered on a
     * different line than their regular personal M-Pesa number, so reusing
     * mpesaPhoneNumber for B2Pochi risks sending the payout to a number that
     * doesn't actually have a Pochi account (or, worse, one that happens to
     * belong to someone else's Pochi account entirely). Resolved
     * authoritatively from here — never taken from the disbursement request
     * itself — same reasoning as mpesaPhoneNumber/paypalEmail.
     *
     * SECURITY: for B2Pochi withdrawals this being unset is a hard
     * rejection — DisbursementService.resolveVerifiedPochiPhoneNumber
     * requires it to be present and throws PhoneNumberUnavailableException
     * otherwise. There is intentionally NO fallback to mpesaPhoneNumber (a
     * user's Pochi account can live on a different line than their regular
     * M-Pesa number, so reusing it risks paying out to a number with no
     * Pochi account, or someone else's Pochi account) and no fallback to
     * any external source either. Stored normalized to 254XXXXXXXXX. Set
     * via PUT /wallet/pochi-phone.
     */
    private String pochiPhoneNumber;

    /**
     * Stripe Customer object id (cus_xxx) for this wallet's owner. Created
     * lazily on first Stripe deposit or first setup-intent request. A
     * test-mode customer and a live-mode customer are entirely separate
     * objects in Stripe — this id is only valid for whichever mode
     * (sandbox/production) it was created under.
     */
    @Indexed(unique = true, sparse = true)
    private String stripeCustomerId;

    /**
     * The PaymentMethod (pm_xxx) currently saved for one-click deposit
     * reloads. Attached after a successful SetupIntent (see
     * DepositService.confirmStripeSetupIntent) or as a side effect of any
     * successful deposit that requested setup_future_usage. Same
     * sandbox/production separation caveat as stripeCustomerId.
     */
    private String stripeDefaultPaymentMethodId;

    /** Display-only — safe to store (not sensitive), lets the frontend show "Visa •••• 4242" without an extra Stripe call. */
    private String stripeCardBrand;
    private String stripeCardLast4;

    /**
     * Stripe Connect Express account id (acct_xxx) linked for international
     * bank withdrawals — distinct from stripeCustomerId above, which is for
     * charging the user (deposits). This account is created and
     * operationally controlled by Premisave (see StripeService.
     * createConnectedAccountAndOnboardingLink) — Stripe collects the user's
     * KYC/bank details directly via hosted onboarding, never touching our
     * servers, same principle as card data via Stripe.js.
     *
     * Scoped to international users (US/UK/EU bank accounts) — Kenyan
     * payouts go through M-Pesa/Flutterwave instead, since Stripe doesn't
     * support Kenya as either a platform or (self-serve) recipient country.
     *
     * Sandbox and production connected accounts are entirely separate — same
     * caveat as stripeCustomerId. Set via POST /wallet/stripe/connect/link,
     * cleared via DELETE /wallet/stripe/connect/link — see
     * WalletService.disconnectStripeConnectAccount for why "unlink" here
     * doesn't (and can't) revoke anything on Stripe's side the way PayPal's
     * disconnectPaypalAccount comment describes for OAuth-style accounts.
     */
    @Indexed(unique = true, sparse = true)
    private String stripeConnectedAccountId;

    /** Display-only — ISO country code the connected account was onboarded with (e.g. "US", "GB"). */
    private String stripeConnectedAccountCountry;

    /**
     * Whether Stripe has confirmed this connected account can currently
     * receive payouts (onboarding + verification complete). Kept in sync via
     * the "account.updated" Connect webhook (see WalletService.
     * updateStripeConnectAccountStatus) and by the manual
     * POST /wallet/stripe/connect/refresh endpoint. DisbursementService
     * rejects STRIPE withdrawal requests outright while this is false,
     * rather than letting the Transfer+Payout attempt fail at Stripe.
     */
    private boolean stripePayoutsEnabled = false;

    /** Display-only — the linked external bank account's name/last4, so the frontend can show "Chase •••• 4242" without an extra Stripe call. */
    private String stripeExternalBankName;
    private String stripeExternalBankLast4;

    /**
     * PayPal Vault payment token id (see PaypalService Vault methods) for
     * this wallet's saved PayPal account — lets returning depositors pay
     * without a full re-approval flow. Set after a successful vaulted
     * capture (see DepositService.confirmPaypalDepositInternal) or, if
     * vaulting was still processing at capture time (vault.status
     * "APPROVED" rather than "VAULTED"), finalized later via the
     * VAULT.PAYMENT-TOKEN.CREATED webhook (see
     * DepositService.attachPaypalVaultToken). Sandbox and production
     * vault tokens are entirely separate, same caveat as Stripe above.
     */
    @Indexed(sparse = true)
    private String paypalVaultId;

    /**
     * PayPal-generated customer.id tied to this wallet's saved payment
     * source(s). Passed back to PayPal on subsequent createOrder calls
     * alongside paypalVaultId, and used to resolve which wallet a
     * VAULT.PAYMENT-TOKEN.CREATED webhook belongs to when vaulting
     * finalizes asynchronously (see WalletRepository.findByPaypalCustomerId).
     */
    @Indexed(unique = true, sparse = true)
    private String paypalCustomerId;

    /**
     * Display-only — the PayPal account email PayPal itself confirmed
     * during vaulting (returned alongside vault.id at capture). Distinct
     * from paypalEmail above, which is a manually-typed, unverified payout
     * destination. Safe to show in the frontend as "Connected: user@example.com".
     */
    private String paypalConnectedEmail;

    /**
     * The PayPal Vault setup token id issued by DepositService.createPaypalLinkToken,
     * held here until confirmPaypalLink completes (or a new link attempt overwrites
     * it). Ownership of a link-confirmation request is checked against this value —
     * NOT against PayPal's returned customer.id, which for PayPal-wallet vaulting
     * (unlike card vaulting) is a PayPal-generated identifier unrelated to whatever
     * merchant customer.id was supplied at setup-token creation, so it cannot be used
     * to verify the setup token belongs to this wallet. Cleared back to null once
     * confirmPaypalLink succeeds.
     */
    private String pendingPaypalSetupTokenId;

    /**
     * Flutterwave customer id (cus_xxx) for this wallet's owner. Created
     * lazily on first Flutterwave deposit if account-linking is implemented.
     * Sandbox and production customer ids are entirely separate — this id is
     * only valid for whichever environment it was created under.
     * Currently not persisted (account linking not yet implemented), but
     * field reserved for future use — see DepositService.initiateMobileMoneyCharge.
     */
    @Indexed(unique = true, sparse = true)
    private String flutterwaveCustomerId;

    /**
     * Flutterwave payment method id (pm_xxx) currently saved for this wallet's
     * owner — lets returning depositors reuse their mobile-money account
     * without re-entering phone/network every time. Set after a successful
     * deposit if account linking is implemented (currently not wired up).
     * Sandbox and production payment method ids are entirely separate.
     */
    private String flutterwavePaymentMethodId;

    /**
     * Display-only — the mobile network code (e.g., "MTN", "AIRTEL") for
     * the saved Flutterwave payment method, shown to the frontend as
     * "Connected: MTN (+233)". Null if no Flutterwave account is linked.
     */
    private String flutterwavePaymentMethodNetwork;

    /**
     * Display-only — the phone number for the saved Flutterwave payment
     * method, shown to the frontend as "Connected: MTN (+233 505-xxx-xxxx)".
     * Null if no Flutterwave account is linked.
     */
    private String flutterwavePaymentMethodPhone;

    /**
     * The Flutterwave setup token id issued by a potential future
     * DepositService.createFlutterwaveLinkToken (not yet implemented),
     * held here until confirmFlutterwaveLink completes. Same pattern as
     * pendingPaypalSetupTokenId above — used to verify ownership of
     * link-confirmation requests. Cleared back to null once linking succeeds.
     * Currently not used; field reserved for future account-linking feature.
     */
    private String pendingFlutterwaveSetupTokenId;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}