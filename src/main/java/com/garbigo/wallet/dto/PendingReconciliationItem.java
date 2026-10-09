package com.premisave.wallet.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single pending item, generically, regardless of which entity it
 * actually is — the whole point of the dashboard this powers
 * (GET /admin/reconciliation/pending) is discovery across everything at
 * once, not a full detail view of any one entity.
 *
 * Deliberately separates two different audiences:
 *  - displaySummary/resolutionMessage: plain language, for a human admin
 *    reading this directly — no HTTP methods, no endpoint paths, no API
 *    jargon at all.
 *  - actionCode: a short, stable machine-readable value a real frontend
 *    can key off to decide which button(s) to render, without needing to
 *    parse resolutionMessage's English text to figure out what's
 *    possible. One of: "APPROVE_REJECT_DEPOSIT",
 *    "APPROVE_REJECT_DISBURSEMENT", "APPROVE_REJECT_REVERSAL",
 *    "CLOSE_OPERATION", "NEEDS_MANUAL_REVIEW".
 */
@Data
public class PendingReconciliationItem {
    private String entityType;

    /** Plain-language label, e.g. "M-Pesa Payout", "M-Pesa Balance Check" — never the raw entityType/enum value. */
    private String displaySummary;

    private String id;
    private String userId;
    private BigDecimal amount;
    private String provider;
    private String reference;
    private LocalDateTime createdAt;
    private long minutesPending;
    private boolean resolvableViaApi;

    /** Machine-readable — see class javadoc for the fixed set of values. */
    private String actionCode;

    /** Plain-language explanation of what this is and what can be done about it — no API/HTTP details at all. */
    private String resolutionMessage;
}