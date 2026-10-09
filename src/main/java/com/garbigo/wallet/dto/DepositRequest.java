package com.premisave.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DepositRequest {

    @NotNull
    @DecimalMin("1.00")
    private BigDecimal amount;

    /**
     * Payment provider: MPESA | MPESA_TILL | STRIPE | PAYPAL | FLUTTERWAVE | NOWPAYMENTS
     * Defaults to MPESA if omitted.
     */
    private String provider;

    /**
     * Used for M-Pesa STK push (provider=MPESA) — the customer's Safaricom
     * number. Format: 07xxxxxxxx or 254xxxxxxxx. Optional if the wallet has
     * a saved M-Pesa phone number (see PUT /wallet/mpesa-phone) — that's
     * used automatically when this is omitted, so repeat deposits don't
     * require typing a number every time.
     */
    private String phoneNumber;

    /**
     * Required for provider=MPESA_TILL (B2B Express Checkout) — the paying
     * merchant's own till number. The USSD PIN prompt is sent to whichever
     * phone is registered as that till's nominated operator number, not to
     * a number you supply directly.
     */
    private String payerTillNumber;

    /**
     * ISO 4217 currency code (e.g. KES, USD, EUR) for most providers.
     * Defaults to KES for M-Pesa, USD for PayPal and Flutterwave.
     *
     * EXCEPTION — provider=NOWPAYMENTS: this is the CRYPTOCURRENCY the
     * customer will pay in (e.g. "usdttrc20", "btc"), not a fiat code.
     * Which FIAT currency amount is priced in is a SEPARATE field —
     * see nowPaymentsPriceCurrency below. See
     * NowPaymentsDepositService.initiateNowPaymentsDeposit.
     */
    private String currency;

    /**
     * Optional, provider=FLUTTERWAVE only — restricts which channels
     * Flutterwave's hosted checkout page shows, e.g. "card",
     * "banktransfer", "ussd", "mobilemoneyghana,mobilemoneyuganda,mobilemoneyrwanda,mobilemoneyzambia,mobilemoneyfranco",
     * or any comma-separated combination. Left null/blank to let
     * Flutterwave show every channel enabled on the account.
     *
     * Kenyan mobile money is intentionally not offered through this path —
     * use provider=MPESA for that (direct Daraja STK Push integration).
     */
    private String flutterwavePaymentOptions;

    /**
     * Optional, provider=FLUTTERWAVE only — customer display name/phone
     * pre-filled on Flutterwave's checkout page. Both optional; the
     * customer's email is always taken from the authenticated user, never
     * from this request.
     */
    private String customerName;
    private String customerPhone;

    /**
     * Required for provider=FLUTTERWAVE — the mobile network's country
     * dialling code (e.g. "233" for Ghana). Passed to Flutterwave's v4
     * mobile_money payment method. See
     * FlutterwaveService.initiateMobileMoneyCharge.
     */
    private String flutterwaveCountryCode;

    /**
     * Required for provider=FLUTTERWAVE — Flutterwave's network code for
     * the customer's mobile money corridor (e.g. "MTN", "AIRTEL"). Confirm
     * exact network codes for your target corridors against Flutterwave's
     * Mobile Money docs before going live with a new country.
     */
    private String flutterwaveMobileNetwork;

    /**
     * SANDBOX TESTING ONLY, provider=NOWPAYMENTS only — one of
     * "success", "failed", "partially_paid", or "common" (NOWPayments'
     * own valid values, confirmed via their API's error response — "finished"
     * is NOT valid here despite being a real payment_status value elsewhere;
     * don't confuse the two). Passed straight through to NOWPayments'
     * Create Payment "case" parameter, which makes it immediately simulate
     * that outcome via a synthetic IPN callback — no real crypto needed,
     * same purpose as flutterwave.sandbox-scenario-key, just per-request
     * here instead of account-wide, since NOWPayments' own API takes it
     * per-payment rather than per-account.
     *
     * NOWPayments' production environment simply doesn't recognize "case"
     * at all — leave this null/blank in production requests regardless.
     */
    private String nowPaymentsSandboxCase;

    /**
     * Optional, provider=NOWPAYMENTS only — which FIAT currency
     * request.getAmount() is denominated in for pricing purposes (e.g.
     * "usd", "eur", "kes"). Defaults to "usd" if omitted — USD is the
     * conventional reference currency for crypto pricing internationally,
     * and forcing every depositor through KES specifically excludes
     * non-Kenyan users who don't think in KES terms. A Kenya-based user
     * who prefers to think in KES can still pass "kes" explicitly.
     *
     * This is genuinely different from every other provider's amount
     * semantics: everywhere else, request.getAmount() IS a KES figure
     * credited to the wallet as-is. Here, it's priced in WHATEVER
     * currency this field says, then converted to KES via FxRateService
     * at initiation time (skipped entirely if this is "kes") — see
     * NowPaymentsDepositService.initiateNowPaymentsDeposit.
     */
    private String nowPaymentsPriceCurrency;
}