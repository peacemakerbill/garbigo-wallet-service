package com.premisave.wallet.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Detailed platform balance snapshot — GET /admin/finance/reports/balance-overview.
 * Built fresh against the real Wallet entity rather than extending
 * AdminWalletService's existing balance-overview endpoint, whose
 * implementation was never seen in this session.
 */
@Data
public class BalanceOverviewResponse {
    private long totalWalletCount;
    private BigDecimal totalPlatformBalance;

    private long activeWalletCount;
    private BigDecimal activeBalance;

    private long frozenWalletCount;
    private BigDecimal frozenBalance;

    private BigDecimal averageBalance;

    // Provider linkage adoption — how many wallets have actually connected each provider.
    private long walletsWithStripeCustomer;
    private long walletsWithStripeConnectedAccount;
    private long walletsWithPaypalLinked;
    private long walletsWithMpesaPhoneLinked;
    private long walletsWithFlutterwaveLinked;

    /** All-time company commission revenue, for context alongside how much customer money is currently held. */
    private BigDecimal totalCommissionRevenueAllTime;

    /** Highest-balance wallets — admin/risk visibility. Only email + balance exposed, no other wallet fields. */
    private List<TopWalletEntry> topWalletsByBalance;

    @Data
    public static class TopWalletEntry {
        private String accountNumber;
        private BigDecimal balance;
    }
}