package com.premisave.wallet.dto;

import com.premisave.wallet.enums.DisbursementStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single Disbursement record for history display — distinct from
 * DisbursementResponse's role (the generic id/status/message shape
 * returned when a disbursement is first initiated). This is what
 * GET /disbursements/history returns: the full, already-resolved record,
 * including totalDebited/commissionRate so a user can see exactly what
 * they were charged versus what actually went out.
 */
@Data
public class DisbursementRecordResponse {
    private String id;
    private String userId;
    private BigDecimal amount;
    private BigDecimal totalDebited;
    private BigDecimal commissionRate;

    /** Plain currency code (e.g. "KES", "USD") — matches Disbursement.currency's own type, not the Currency enum. */
    private String currency;

    /**
     * The real, native-currency figure actually sent to the external
     * destination (e.g. the real KES amount an M-Pesa recipient's phone
     * received) — see Disbursement.nativeAmount's own javadoc. Null for
     * a provider whose native payout currency already IS USD (Stripe,
     * PayPal, NOWPayments), since amount/currency above already ARE the
     * native figure in that case.
     */
    private BigDecimal nativeAmount;

    /** Which currency nativeAmount is denominated in (e.g. "KES") — null under the same condition as nativeAmount. */
    private String nativeCurrency;

    private String destination;
    private String provider;
    private String channel;
    private DisbursementStatus status;
    private String reference;
    private String providerReference;
    private String failureReason;
    private LocalDateTime createdAt;
}