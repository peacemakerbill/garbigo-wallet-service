package com.premisave.wallet.entity;

import com.premisave.wallet.enums.Currency;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * General company profit/loss ledger — deliberately NOT commission-only.
 * A single, rich entity for every kind of financial gain or loss the
 * company itself experiences: commissions taken from transfers/
 * disbursements, direct revenue (ad subscriptions, booking fees via
 * Payment), and — since amount is signed — future loss categories
 * (refunds, waived fees, chargebacks) without needing a schema change or
 * a second entity. Profit & loss reporting is then just "sum amount over
 * a date range / group by type," not a join across five different
 * transaction-type tables.
 *
 * Genuinely separate from Deposit/Disbursement/Transfer/Payment — those
 * track what happened to a USER's wallet; this tracks what happened to
 * the COMPANY's bottom line, which may or may not correspond 1:1 with
 * any single user-facing transaction (a commission entry references the
 * transfer/disbursement that generated it, but is its own distinct
 * financial event).
 */
@Data
@Document(collection = "company_ledger")
public class CompanyLedgerEntry {

    @Id
    private String id;

    /**
     * Signed — POSITIVE for a gain (commission earned, subscription
     * revenue), NEGATIVE for a loss (refund, waived fee, chargeback).
     * Summing this column directly over any date range or type filter
     * gives net profit/loss with no special-casing needed.
     */
    private BigDecimal amount;

    private Currency currency;

    /**
     * Free-text category, e.g. "COMMISSION_TRANSFER",
     * "COMMISSION_DISBURSEMENT", "AD_SUBSCRIPTION", "BOOKING_FEE",
     * "REFUND_LOSS" — deliberately a plain String, not a rigid enum,
     * matching Deposit.provider/Disbursement.provider's convention. New
     * gain/loss categories can be introduced without a code deploy.
     */
    private String type;

    private String description;

    /**
     * What kind of record generated this entry — "TRANSFER",
     * "DISBURSEMENT", "PAYMENT", or null for an entry with no underlying
     * user transaction at all (e.g. a manual accounting adjustment).
     */
    private String sourceType;

    /** The actual Transfer/Disbursement/Payment id this entry was derived from, for audit traceability back to the real event. */
    private String sourceId;

    /** The source record's own reference/idempotency key — lets this be cross-looked-up without needing sourceId's exact collection first. */
    private String sourceReference;

    /**
     * The commission rate actually applied, if this entry came from a
     * rate calculation (e.g. 0.10 for 10%) — null for direct revenue
     * (ad subscriptions) where there's no rate at all, the whole amount
     * IS the revenue.
     */
    private BigDecimal rateApplied;

    /** The original transfer/disbursement amount the rate was applied TO, if applicable — null when rateApplied is null. */
    private BigDecimal grossAmount;

    /** Which user's action generated this entry, if any — lets you report "which users generate the most commission," not just aggregate totals. */
    private String userId;

    @CreatedDate
    private LocalDateTime createdAt;
}