package com.premisave.wallet.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * All-time platform summary — GET /admin/finance/reports/summary. Built
 * fresh against every entity from tonight's work, same reasoning as the
 * daily report and balance overview: AdminWalletService's existing
 * getSystemSummary was never seen in this session.
 *
 * Distinct in scope from the other two admin reports, not a duplicate:
 * DailyFinanceReportResponse covers ONE day; BalanceOverviewResponse
 * covers CURRENT wallet state; this covers the platform's entire
 * lifetime — total volume ever processed, not activity in any one window.
 */
@Data
public class SystemSummaryResponse {

    // ─── Wallets (current state, for context) ─────────────────────
    private long totalWalletCount;
    private long activeWalletCount;
    private long frozenWalletCount;
    private BigDecimal totalPlatformBalance;

    // ─── Deposits (all-time) ────────────────────────────────────────
    private long totalDepositCount;
    private BigDecimal totalDepositVolume;
    private Map<String, Long> depositCountByStatus;
    private Map<String, BigDecimal> depositVolumeByProvider;

    // ─── Disbursements (all-time) ───────────────────────────────────
    private long totalDisbursementCount;
    /** Sum of `amount` — what actually reached customers/providers, unaffected by commission. */
    private BigDecimal totalDisbursementVolume;
    /** Sum of `totalDebited` — what actually left customer wallets, amount + commission. */
    private BigDecimal totalDisbursementDebited;
    private Map<String, Long> disbursementCountByStatus;
    private Map<String, BigDecimal> disbursementVolumeByProvider;

    // ─── Transfers (all-time) ────────────────────────────────────────
    private long totalTransferCount;
    private BigDecimal totalTransferVolume;
    private BigDecimal totalTransferCommission;

    // ─── Payments (all-time) ─────────────────────────────────────────
    private long totalPaymentCount;
    private BigDecimal totalPaymentVolume;
    private Map<String, BigDecimal> paymentVolumeByService;

    // ─── Company revenue (all-time) ──────────────────────────────────
    private BigDecimal totalCommissionFromTransfers;
    private BigDecimal totalCommissionFromDisbursements;
    /** Sum of CompanyLedgerEntry rows with sourceType="PAYMENT" (ad subscriptions, booking fees, etc.) — the FULL amount, not a rate cut, since a Payment IS company revenue directly */
    private BigDecimal totalRevenueFromPayments;
    private BigDecimal totalCommissionRevenue;
}