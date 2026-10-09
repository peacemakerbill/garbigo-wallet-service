package com.premisave.wallet.entity;

import com.premisave.wallet.enums.DisbursementStatus;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Document(collection = "disbursements")
public class Disbursement {

    @Id
    private String id;

    private String userId;
    private String walletId;

    /**
     * The requested payout amount, before commission. For MPESA, STRIPE,
     * PAYPAL, and NOWPAYMENTS, this is USD — the wallet-side truth,
     * matching totalDebited/totalDebitedUsd below (explicit request,
     * confirmed: "the data saved should always be in dollars... not
     * Kenyan shillings anywhere" — previously MPESA recorded this in raw
     * KES, which is what actually distorted CompanyLedgerEntry sums and
     * AdminReportService's cross-provider totals, since those were
     * summing native-currency figures from different providers together
     * as if they were the same unit). FLUTTERWAVE is the one exception,
     * still genuinely native (destinationCurrency) — not touched by this
     * change, since it wasn't part of what was reported as affected. See
     * nativeAmount below for the real, native-currency figure that was
     * actually sent, on every provider where amount here is USD.
     */
    private BigDecimal amount;

    /**
     * A plain ISO-4217-style code (e.g. "KES", "USD"), NOT the Currency
     * enum — unlike Wallet/Deposit/Transaction, which stay on the enum
     * since those represent an internal, always-known value (in
     * practice, always USD post-conversion). Kept as a String rather
     * than reverted to the enum even now that MPESA/STRIPE/PAYPAL/
     * NOWPAYMENTS are USD here, since FLUTTERWAVE's native payout
     * currency still can genuinely be anything a gateway supports
     * (Flutterwave alone covers many countries/currencies), and the
     * fixed three-value enum (KES/USD/EUR) can't represent that. Mirrors
     * Deposit.priceCurrency, which was already a plain String for the
     * same underlying reason.
     */
    private String currency;

    /**
     * What actually gets/got debited from the wallet at confirmation time
     * — amount + gateway commission (see CommissionService,
     * commission.gateway-rate). The external provider/recipient still
     * receives exactly `amount`, unaffected — the commission is charged
     * ON TOP of the withdrawal, not deducted from it, same design as
     * Transfer.totalDebited. Equal to amount when the configured rate is
     * zero, or null for a Disbursement created before this field existed
     * (admin B2B/B2C top-up, which deliberately don't carry commission
     * at all — see MpesaDisbursementService).
     */
    private BigDecimal totalDebited;

    /**
     * totalDebited converted to USD at INITIATION time, not re-derived at
     * completion — same principle as commissionRate above: lock in what
     * was actually current when the withdrawal was first requested,
     * rather than letting every downstream path independently decide
     * whether/how to convert. The wallet is fixed at USD, but
     * totalDebited/amount/currency represent the real, native payout
     * (KES for M-Pesa, whatever destinationCurrency for Flutterwave,
     * already USD for Stripe/PayPal/NOWPayments — where this simply
     * equals totalDebited, no real conversion needed).
     *
     * Every path that eventually debits the wallet for this disbursement
     * — the automatic webhook completion (completeMpesaDisbursement /
     * completeFlutterwaveDisbursement) AND the manual admin-approval
     * path (adminApproveDisbursement) — reads this SAME value, rather
     * than each needing its own independent conversion call. This
     * directly closes a real gap: adminApproveDisbursement was debiting
     * the wallet with the raw native-currency totalDebited, with zero
     * conversion, since it never had its own call to
     * ExchangeRateService — a mistake this field's whole design exists
     * to prevent from happening again at some other future completion
     * path.
     *
     * Null for a disbursement created before this field existed — the
     * completion paths fall back to converting at completion time using
     * the current rate in that case.
     */
    private BigDecimal totalDebitedUsd;

    /**
     * The exact commission rate applied at INITIATION time, not
     * re-derived from current config at confirmation time — since a
     * disbursement can sit PENDING for a while awaiting a webhook, and
     * commission.gateway-rate could theoretically change in that window,
     * this locks in what was actually agreed to when the withdrawal was
     * first requested, so a later config change can't retroactively
     * change what gets recorded in the company ledger for an in-flight
     * disbursement.
     */
    private BigDecimal commissionRate;

    private String destination; // phone number, paypal email, receiver shortcode, etc.

    /**
     * The wallet owner's (the sender's) real name — resolved via the
     * auth service (see UserNameResolver) at confirmation time, using
     * Wallet.accountNumber (the owner's email) as the lookup key. Null if
     * that lookup failed, or the account genuinely has no name on file.
     * Distinct from verifiedRecipientName below, which is the EXTERNAL
     * receiving organization's name for a B2B payment (not a Premisave
     * user at all) — this field is always about the wallet owner
     * initiating the withdrawal.
     */
    private String senderName;

    /**
     * The real, native-currency figure actually sent to the external
     * destination — e.g. the real KES amount an M-Pesa recipient's phone
     * received, distinct from amount/currency above (which are always
     * USD — see Disbursement's class-level currency-model note). Mirrors
     * Deposit.priceAmount exactly, same role on the opposite side of the
     * wallet: preserves the real, externally-verifiable figure alongside
     * the USD wallet-side truth, rather than losing it. Null for a
     * provider whose native payout currency already IS USD (Stripe,
     * PayPal, NOWPayments), since there's nothing distinct to preserve
     * there — amount/currency already ARE the native figure in that case.
     */
    private BigDecimal nativeAmount;

    /** Which currency nativeAmount is denominated in (e.g. "KES") — null under the same condition as nativeAmount. */
    private String nativeCurrency;

    private String provider;    // MPESA, PAYPAL, STRIPE, FLUTTERWAVE, NOWPAYMENTS
    private String channel;     // B2C, B2C_POCHI, B2B, B2C_TOPUP, PAYPAL_PAYOUT, STRIPE_PAYOUT, FLUTTERWAVE_BANK, FLUTTERWAVE_MOBILE_MONEY, NOWPAYMENTS_PAYOUT

    private DisbursementStatus status = DisbursementStatus.PENDING;

    private String reference;

    @Indexed
    private String providerReference; // ConversationID (M-Pesa), Payout Batch ID (PayPal), Transfer ID (Flutterwave), Payout ID (NOWPayments) — used to reconcile async result callbacks

    private String failureReason;

    /**
     * Populated for B2B payments where MpesaB2BRequest.verifyRecipient=true —
     * the organization name Safaricom's "B2B Hakikisha" (Query Org Info)
     * check confirmed for the receiverShortcode before the payment was sent.
     * Null if verification wasn't requested or wasn't applicable (non-B2B channels).
     */
    private String verifiedRecipientName;

    /** Charge/tariff profile ID returned by the same Hakikisha check, if any. */
    private String verifiedChargeProfileId;

    /**
     * Flutterwave transfers require a recipient object created first, then a
     * transfer referencing that recipient. If recipient creation succeeds but
     * transfer initiation fails, this caches the recipient ID so a retry can
     * reuse it and avoid creating orphaned duplicates. Populated only for
     * FLUTTERWAVE disbursements; null for all other providers.
     */
    private String flutterwaveRecipientId;

    @CreatedDate
    private LocalDateTime createdAt;
}