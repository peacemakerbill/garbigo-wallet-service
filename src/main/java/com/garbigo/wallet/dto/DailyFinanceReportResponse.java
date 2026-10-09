package com.premisave.wallet.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * A single day's complete financial picture across every entity built
 * tonight — Deposit, Disbursement, Transfer, Payment, and the company
 * commission ledger. Deliberately built fresh against these, rather than
 * extending AdminWalletService's existing getDailyReport, which almost
 * certainly predates all of them and likely only ever knew about the
 * generic Transaction table.
 */
@Data
public class DailyFinanceReportResponse {
    private LocalDate date;

    // ─── Deposits (money coming in) ──────────────────────────────
    private long depositCount;
    private BigDecimal totalDepositAmount;
    private Map<String, BigDecimal> depositAmountByProvider;
    private Map<String, Long> depositCountByStatus;

    // ─── Disbursements (money going out) ─────────────────────────
    private long disbursementCount;
    /** Sum of `amount` — what actually reached the customer/provider, unaffected by commission. */
    private BigDecimal totalDisbursementAmount;
    /** Sum of `totalDebited` — what actually left customer wallets, amount + commission. */
    private BigDecimal totalDisbursementDebited;
    private Map<String, BigDecimal> disbursementAmountByProvider;
    private Map<String, Long> disbursementCountByStatus;

    // ─── Transfers (wallet-to-wallet) ─────────────────────────────
    private long transferCount;
    private BigDecimal totalTransferAmount;
    private BigDecimal totalTransferCommission;

    // ─── Payments (wallet-to-platform, e.g. ad subscriptions) ─────
    private long paymentCount;
    private BigDecimal totalPaymentAmount;
    private Map<String, BigDecimal> paymentAmountByService;

    // ─── Company revenue (the actual answer to "how much did we make today") ───
    private BigDecimal totalCommissionFromTransfers;
    private BigDecimal totalCommissionFromDisbursements;
    /** Sum of CompanyLedgerEntry rows with sourceType="PAYMENT" (ad subscriptions, booking fees, etc.) — the FULL amount, not a rate cut, since a Payment IS company revenue directly (see PaymentService/CompanyLedgerEntry javadocs). */
    private BigDecimal totalRevenueFromPayments;
    private BigDecimal totalCommissionRevenue;
}