package com.premisave.wallet.entity;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DepositStatus;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Deposit lifecycle tracking — mirrors Disbursement exactly, on the
 * opposite side of the wallet: a dedicated entity with real structured
 * fields, rather than deposits continuing to exist ONLY as a generic
 * Transaction row (type=DEPOSIT) with provider-specific detail crammed
 * into a free-text description string — which is what every deposit
 * service in this codebase did before this entity existed.
 *
 * Same two-record pattern Disbursement already uses: this entity tracks
 * the full lifecycle (PENDING through webhook confirmation), and a
 * separate Transaction row is created only once SUCCESS is confirmed —
 * see DepositTransactionRecorder, mirroring DisbursementTransactionRecorder.
 */
@Data
@Document(collection = "deposits")
public class Deposit {

    @Id
    private String id;

    private String userId;
    private String walletId;

    private BigDecimal amount; // always KES — the amount actually credited to the wallet on confirmation
    private Currency currency; // always Currency.KES, kept for symmetry with Disbursement.currency

    /**
     * Where the money is coming FROM, when a provider requires collecting
     * this upfront — e.g. the customer's phone number for an M-Pesa STK
     * push. Null for providers where no such identifier is collected
     * (Stripe, PayPal, Flutterwave card/hosted-checkout flows, NOWPayments'
     * anonymous pay-to-address flow). Inverse role of Disbursement.destination.
     */
    private String source;

    /**
     * The paying party's real name, when the gateway's own callback
     * provides one — confirmed for M-Pesa C2B (Safaricom's confirmation
     * payload includes FirstName/MiddleName/LastName for whoever paid).
     * Null for a self-deposit (M-Pesa STK push — sender and recipient are
     * the same wallet owner, so there's no separate identity to record
     * here) and for gateways with no equivalent field in their own
     * callback data (Stripe, PayPal, Flutterwave, NOWPayments).
     */
    private String senderName;

    /**
     * The wallet owner's (the recipient's) real name — resolved via the
     * auth service (see UserNameResolver) at confirmation time, using
     * Wallet.accountNumber (the owner's email) as the lookup key. Null if
     * that lookup failed, or the account genuinely has no name on file.
     */
    private String recipientName;

    private String provider; // MPESA, STRIPE, PAYPAL, FLUTTERWAVE, NOWPAYMENTS
    private String channel;  // MPESA_STK, MPESA_TILL, STRIPE_CARD, NOWPAYMENTS_CRYPTO, etc.

    private DepositStatus status = DepositStatus.PENDING;

    private String reference; // idempotency key — order_id / CheckoutRequestID / etc.

    @Indexed
    private String providerReference; // payment_id (NOWPayments), PaymentIntent id (Stripe), CheckoutRequestID (M-Pesa), etc.

    private String failureReason;

    // ── NOWPayments-specific fields — null for every other provider ──
    // Same pattern as Disbursement.flutterwaveRecipientId: a field only
    // one integration ever populates, kept here rather than in a separate
    // table since NOWPayments is the only provider whose deposit involves
    // genuinely structured extra state (a quoted crypto amount and a
    // one-time address) that doesn't fit any other provider's shape.

    /** The one-time crypto address the customer was told to pay. */
    private String payAddress;

    /** Quoted crypto amount, as returned by NOWPayments (kept as String — see NowPaymentsService.CreatePaymentResult). */
    private String payAmount;

    /** Which cryptocurrency the customer is paying in (e.g. "usdttrc20"). */
    private String payCurrency;

    /** The fiat amount the deposit was originally priced in — see DepositRequest.nowPaymentsPriceCurrency. */
    private BigDecimal priceAmount;

    /** Which fiat currency priceAmount is denominated in (e.g. "usd"). */
    private String priceCurrency;

    @CreatedDate
    private LocalDateTime createdAt;
}