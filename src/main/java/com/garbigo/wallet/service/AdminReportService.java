package com.premisave.wallet.service;

import com.premisave.wallet.dto.BalanceOverviewResponse;
import com.premisave.wallet.dto.DailyFinanceReportResponse;
import com.premisave.wallet.dto.SystemSummaryResponse;
import com.premisave.wallet.entity.CompanyLedgerEntry;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Payment;
import com.premisave.wallet.entity.Transfer;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.repository.WalletRepository;
import com.premisave.wallet.util.DateRangeCriteriaUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds the comprehensive daily financial report — GET
 * /admin/finance/reports/daily (see AdminFinanceController). Cross-entity
 * aggregation genuinely doesn't belong to any single one of
 * DepositService/DisbursementService/TransferService/PaymentService/
 * CommissionService, so it lives here instead, injecting MongoTemplate
 * directly rather than going through each service's own methods (which
 * return DTOs already shaped for history display, not raw entities
 * convenient for aggregation).
 *
 * Uses simple Java-stream grouping/summing over a single day's records
 * rather than a native MongoDB aggregation pipeline — a day's volume is
 * expected to be a small, bounded dataset, and this is easier to read
 * and verify correct than the more verbose Aggregation/GroupOperation
 * API. Worth revisiting if daily volume ever grows large enough for that
 * tradeoff to flip.
 *
 * CURRENCY NORMALIZATION (added): explicit request — "the reports should
 * also be showing data in USD, if there were any records saved in
 * different currencies before, conversion should take place." Applied
 * selectively, not uniformly, since blindly converting every
 * currency-labeled field would be WRONG for some entities:
 *
 * - Deposit.amount / Disbursement.amount+totalDebited: converted per-
 *   record using that record's OWN currency field, which reliably
 *   describes what amount is actually denominated in throughout its
 *   history (this was never a pure mislabeling bug for these two — the
 *   stored NUMBER itself was genuinely native-currency-magnitude before
 *   the fixes made earlier this session).
 *
 * - Transfer.amount / Payment.amount: deliberately NEVER converted.
 *   These were ALWAYS computed directly against the sender/payer's USD
 *   wallet balance (confirmed by reading TransferService/PaymentService's
 *   actual debit code) — the only bug was a hardcoded "KES" LABEL on an
 *   already-correct USD number. Converting based on that stale label
 *   would introduce a NEW error (shrinking an already-correct figure),
 *   not fix one.
 *
 * - CompanyLedgerEntry: see ledgerEntryAmountUsd's own javadoc — the most
 *   nuanced case, since its historical currency label's reliability
 *   depends on which type of entry it is.
 *
 * All conversions use TODAY's live exchange rate, not the rate actually
 * in effect when a historical record was created (not preserved on most
 * of these fields) — an approximation, not a byte-for-byte historical
 * reconstruction. Disbursement.totalDebitedUsd is the one exception:
 * where present, it's used directly (it locks in the real
 * initiation-time rate), rather than re-converting totalDebited with
 * today's rate.
 */
@Service
@RequiredArgsConstructor
public class AdminReportService {

    private final MongoTemplate mongoTemplate;
    private final WalletRepository walletRepository;
    private final ExchangeRateService exchangeRateService;

    public DailyFinanceReportResponse getDailyReport(LocalDate date) {
        Criteria dayRange = DateRangeCriteriaUtil.applyDateRange(new Criteria(), "createdAt", date, date);

        List<Deposit> deposits = mongoTemplate.find(new Query(dayRange), Deposit.class);
        List<Disbursement> disbursements = mongoTemplate.find(new Query(dayRange), Disbursement.class);
        List<Transfer> transfers = mongoTemplate.find(new Query(dayRange), Transfer.class);
        List<Payment> payments = mongoTemplate.find(new Query(dayRange), Payment.class);
        List<CompanyLedgerEntry> ledgerEntries = mongoTemplate.find(new Query(dayRange), CompanyLedgerEntry.class);

        DailyFinanceReportResponse report = new DailyFinanceReportResponse();
        report.setDate(date);

        // ─── Deposits ───
        report.setDepositCount(deposits.size());
        report.setTotalDepositAmount(sumAmounts(deposits, this::depositAmountUsd));
        report.setDepositAmountByProvider(groupAndSum(deposits, Deposit::getProvider, this::depositAmountUsd));
        report.setDepositCountByStatus(deposits.stream()
                .collect(Collectors.groupingBy(d -> d.getStatus().name(), Collectors.counting())));

        // ─── Disbursements ───
        report.setDisbursementCount(disbursements.size());
        report.setTotalDisbursementAmount(sumAmounts(disbursements, this::disbursementAmountUsd));
        report.setTotalDisbursementDebited(sumAmounts(disbursements, this::disbursementDebitedUsd));
        report.setDisbursementAmountByProvider(groupAndSum(disbursements, Disbursement::getProvider, this::disbursementAmountUsd));
        report.setDisbursementCountByStatus(disbursements.stream()
                .collect(Collectors.groupingBy(d -> d.getStatus().name(), Collectors.counting())));

        // ─── Transfers ─── (never converted -- see class javadoc)
        report.setTransferCount(transfers.size());
        report.setTotalTransferAmount(sumAmounts(transfers, Transfer::getAmount));
        report.setTotalTransferCommission(sumAmounts(transfers,
                t -> t.getTotalDebited() != null ? t.getTotalDebited().subtract(t.getAmount()) : BigDecimal.ZERO));

        // ─── Payments ─── (never converted -- see class javadoc)
        report.setPaymentCount(payments.size());
        report.setTotalPaymentAmount(sumAmounts(payments, Payment::getAmount));
        report.setPaymentAmountByService(groupAndSum(payments, Payment::getService, Payment::getAmount));

        // ─── Company revenue — the actual "how much did we make today" answer ───
        // Targeted query for exactly the Disbursements these ledger
        // entries reference, rather than reusing the day-scoped
        // `disbursements` list above — a disbursement can be INITIATED
        // on one day and have its commission RECORDED on a later day
        // (async webhook completion), so the source disbursement isn't
        // guaranteed to fall within this same day's range.
        Map<String, Disbursement> disbursementsById = fetchDisbursementSources(ledgerEntries);

        BigDecimal commissionFromTransfers = ledgerEntries.stream()
                .filter(e -> "COMMISSION_TRANSFER".equals(e.getType()))
                .map(e -> ledgerEntryAmountUsd(e, disbursementsById))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commissionFromDisbursements = ledgerEntries.stream()
                .filter(e -> "COMMISSION_DISBURSEMENT".equals(e.getType()))
                .map(e -> ledgerEntryAmountUsd(e, disbursementsById))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Filtered by sourceType, not type — type itself varies per
        // Payment.service category ("AD_SUBSCRIPTION", "BOOKING_FEE",
        // etc., see PaymentService.executePayment), while sourceType is
        // the one reliable, constant discriminator every payment-derived
        // ledger entry shares.
        BigDecimal revenueFromPayments = ledgerEntries.stream()
                .filter(e -> "PAYMENT".equals(e.getSourceType()))
                .map(e -> ledgerEntryAmountUsd(e, disbursementsById))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        report.setTotalCommissionFromTransfers(commissionFromTransfers);
        report.setTotalCommissionFromDisbursements(commissionFromDisbursements);
        report.setTotalRevenueFromPayments(revenueFromPayments);
        report.setTotalCommissionRevenue(commissionFromTransfers.add(commissionFromDisbursements).add(revenueFromPayments));

        return report;
    }

    /**
     * Detailed platform balance snapshot — GET
     * /admin/finance/reports/balance-overview. Fetches every Wallet via
     * findAll() and computes statistics in Java, same "small enough
     * dataset, favor readability over a native aggregation pipeline"
     * tradeoff as getDailyReport above — but worth flagging this one
     * scales differently: wallet COUNT only grows over the platform's
     * whole lifetime, unlike a single day's transactional volume, so this
     * is the more likely of the two to eventually need a real MongoDB
     * aggregation pipeline instead, once the user base is large enough
     * for findAll() over every wallet to become a real cost.
     */
    public BalanceOverviewResponse getBalanceOverview(int topN) {
        List<Wallet> wallets = walletRepository.findAll();

        BalanceOverviewResponse r = new BalanceOverviewResponse();
        r.setTotalWalletCount(wallets.size());
        r.setTotalPlatformBalance(sumBalances(wallets));

        List<Wallet> active = wallets.stream().filter(w -> !w.isFrozen()).toList();
        List<Wallet> frozen = wallets.stream().filter(Wallet::isFrozen).toList();

        r.setActiveWalletCount(active.size());
        r.setActiveBalance(sumBalances(active));
        r.setFrozenWalletCount(frozen.size());
        r.setFrozenBalance(sumBalances(frozen));

        r.setAverageBalance(wallets.isEmpty()
                ? BigDecimal.ZERO
                : r.getTotalPlatformBalance().divide(BigDecimal.valueOf(wallets.size()), 2, RoundingMode.HALF_UP));

        r.setWalletsWithStripeCustomer(wallets.stream().filter(w -> w.getStripeCustomerId() != null).count());
        r.setWalletsWithStripeConnectedAccount(wallets.stream().filter(w -> w.getStripeConnectedAccountId() != null).count());
        r.setWalletsWithPaypalLinked(wallets.stream().filter(w -> w.getPaypalVaultId() != null).count());
        r.setWalletsWithMpesaPhoneLinked(wallets.stream().filter(w -> w.getMpesaPhoneNumber() != null).count());
        r.setWalletsWithFlutterwaveLinked(wallets.stream().filter(w -> w.getFlutterwaveCustomerId() != null).count());

        List<CompanyLedgerEntry> allLedgerEntries = mongoTemplate.findAll(CompanyLedgerEntry.class);
        Map<String, Disbursement> disbursementsById = fetchDisbursementSources(allLedgerEntries);
        BigDecimal totalRevenue = allLedgerEntries.stream()
                .filter(e -> e.getAmount() != null)
                .map(e -> ledgerEntryAmountUsd(e, disbursementsById))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        r.setTotalCommissionRevenueAllTime(totalRevenue);

        List<BalanceOverviewResponse.TopWalletEntry> top = wallets.stream()
                .filter(w -> w.getBalance() != null)
                .sorted(Comparator.comparing(Wallet::getBalance).reversed())
                .limit(Math.max(topN, 0))
                .map(w -> {
                    BalanceOverviewResponse.TopWalletEntry entry = new BalanceOverviewResponse.TopWalletEntry();
                    entry.setAccountNumber(w.getAccountNumber());
                    entry.setBalance(w.getBalance());
                    return entry;
                })
                .toList();
        r.setTopWalletsByBalance(top);

        return r;
    }

    /**
     * All-time platform summary — GET /admin/finance/reports/summary.
     * Mostly orchestration, not new logic: reuses sumAmounts/groupAndSum/
     * sumBalances, the same helpers getDailyReport and getBalanceOverview
     * already use, just without a date filter (findAll() per entity
     * rather than a single day's Criteria range).
     *
     * Likely the most expensive of the three admin reports to run at real
     * scale — spans every record ever created across five entities, not
     * one day's activity (getDailyReport) or current wallet state
     * (getBalanceOverview). Same "small enough today, revisit with a real
     * aggregation pipeline once it isn't" flag as the other two.
     */
    public SystemSummaryResponse getSystemSummary() {
        List<Wallet> wallets = walletRepository.findAll();
        List<Deposit> deposits = mongoTemplate.findAll(Deposit.class);
        List<Disbursement> disbursements = mongoTemplate.findAll(Disbursement.class);
        List<Transfer> transfers = mongoTemplate.findAll(Transfer.class);
        List<Payment> payments = mongoTemplate.findAll(Payment.class);
        List<CompanyLedgerEntry> ledgerEntries = mongoTemplate.findAll(CompanyLedgerEntry.class);

        SystemSummaryResponse s = new SystemSummaryResponse();

        // Wallets
        List<Wallet> active = wallets.stream().filter(w -> !w.isFrozen()).toList();
        List<Wallet> frozen = wallets.stream().filter(Wallet::isFrozen).toList();
        s.setTotalWalletCount(wallets.size());
        s.setActiveWalletCount(active.size());
        s.setFrozenWalletCount(frozen.size());
        s.setTotalPlatformBalance(sumBalances(wallets));

        // Deposits
        s.setTotalDepositCount(deposits.size());
        s.setTotalDepositVolume(sumAmounts(deposits, this::depositAmountUsd));
        s.setDepositCountByStatus(deposits.stream()
                .collect(Collectors.groupingBy(d -> d.getStatus().name(), Collectors.counting())));
        s.setDepositVolumeByProvider(groupAndSum(deposits, Deposit::getProvider, this::depositAmountUsd));

        // Disbursements
        s.setTotalDisbursementCount(disbursements.size());
        s.setTotalDisbursementVolume(sumAmounts(disbursements, this::disbursementAmountUsd));
        s.setTotalDisbursementDebited(sumAmounts(disbursements, this::disbursementDebitedUsd));
        s.setDisbursementCountByStatus(disbursements.stream()
                .collect(Collectors.groupingBy(d -> d.getStatus().name(), Collectors.counting())));
        s.setDisbursementVolumeByProvider(groupAndSum(disbursements, Disbursement::getProvider, this::disbursementAmountUsd));

        // Transfers (never converted -- see class javadoc)
        s.setTotalTransferCount(transfers.size());
        s.setTotalTransferVolume(sumAmounts(transfers, Transfer::getAmount));
        s.setTotalTransferCommission(sumAmounts(transfers,
                t -> t.getTotalDebited() != null ? t.getTotalDebited().subtract(t.getAmount()) : BigDecimal.ZERO));

        // Payments (never converted -- see class javadoc)
        s.setTotalPaymentCount(payments.size());
        s.setTotalPaymentVolume(sumAmounts(payments, Payment::getAmount));
        s.setPaymentVolumeByService(groupAndSum(payments, Payment::getService, Payment::getAmount));

        // Company revenue -- disbursements already fetched all-time above, reused directly for the lookup map
        Map<String, Disbursement> disbursementsById = disbursements.stream()
                .filter(d -> d.getId() != null)
                .collect(Collectors.toMap(Disbursement::getId, d -> d, (a, b) -> a));
        BigDecimal commissionFromTransfers = ledgerEntries.stream()
                .filter(e -> "COMMISSION_TRANSFER".equals(e.getType()) && e.getAmount() != null)
                .map(e -> ledgerEntryAmountUsd(e, disbursementsById))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commissionFromDisbursements = ledgerEntries.stream()
                .filter(e -> "COMMISSION_DISBURSEMENT".equals(e.getType()) && e.getAmount() != null)
                .map(e -> ledgerEntryAmountUsd(e, disbursementsById))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal revenueFromPayments = ledgerEntries.stream()
                .filter(e -> "PAYMENT".equals(e.getSourceType()) && e.getAmount() != null)
                .map(e -> ledgerEntryAmountUsd(e, disbursementsById))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        s.setTotalCommissionFromTransfers(commissionFromTransfers);
        s.setTotalCommissionFromDisbursements(commissionFromDisbursements);
        s.setTotalRevenueFromPayments(revenueFromPayments);
        s.setTotalCommissionRevenue(commissionFromTransfers.add(commissionFromDisbursements).add(revenueFromPayments));

        return s;
    }

    private BigDecimal sumBalances(List<Wallet> wallets) {
        return wallets.stream()
                .map(Wallet::getBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private <T> BigDecimal sumAmounts(List<T> items, java.util.function.Function<T, BigDecimal> extractor) {
        return items.stream()
                .map(extractor)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private <T> Map<String, BigDecimal> groupAndSum(List<T> items, java.util.function.Function<T, String> keyFn,
                                                      java.util.function.Function<T, BigDecimal> amountFn) {
        return items.stream()
                .filter(item -> keyFn.apply(item) != null)
                .collect(Collectors.groupingBy(keyFn,
                        Collectors.reducing(BigDecimal.ZERO, amountFn, BigDecimal::add)));
    }

    // ─── Currency normalization ──────────────────────────────────────────────

    /** Deposit.amount, normalized to USD using Deposit's own (reliable) currency field. See class javadoc for the approximation caveat. */
    private BigDecimal depositAmountUsd(Deposit d) {
        if (d.getAmount() == null) return BigDecimal.ZERO;
        return toUsd(d.getAmount(), d.getCurrency() != null ? d.getCurrency().name() : null);
    }

    /** Disbursement.amount, normalized to USD using Disbursement's own (reliable) currency field. */
    private BigDecimal disbursementAmountUsd(Disbursement d) {
        if (d.getAmount() == null) return BigDecimal.ZERO;
        return toUsd(d.getAmount(), d.getCurrency());
    }

    /**
     * Disbursement.totalDebited, normalized to USD — prefers the
     * already-locked-in, historically-accurate totalDebitedUsd when
     * present (set at initiation, see Disbursement.totalDebitedUsd's own
     * javadoc) over re-converting totalDebited with TODAY's live rate,
     * which is only a fallback for a genuinely legacy record created
     * before that field existed.
     */
    private BigDecimal disbursementDebitedUsd(Disbursement d) {
        if (d.getTotalDebitedUsd() != null) {
            return d.getTotalDebitedUsd();
        }
        BigDecimal totalDebited = d.getTotalDebited() != null ? d.getTotalDebited() : d.getAmount();
        if (totalDebited == null) return BigDecimal.ZERO;
        return toUsd(totalDebited, d.getCurrency());
    }

    /** Targeted query for exactly the Disbursements referenced by these ledger entries' sourceId — see getDailyReport's own comment for why this can't just reuse an already-day-scoped disbursements list. */
    private Map<String, Disbursement> fetchDisbursementSources(List<CompanyLedgerEntry> entries) {
        List<String> sourceIds = entries.stream()
                .filter(e -> "COMMISSION_DISBURSEMENT".equals(e.getType()) && e.getSourceId() != null)
                .map(CompanyLedgerEntry::getSourceId)
                .distinct()
                .toList();
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        return mongoTemplate.find(new Query(Criteria.where("_id").in(sourceIds)), Disbursement.class)
                .stream().collect(Collectors.toMap(Disbursement::getId, d -> d, (a, b) -> a));
    }

    /**
     * Normalizes a single CompanyLedgerEntry's amount to USD — see class
     * javadoc's overview. "Intelligence" here means NOT blindly trusting
     * this entry's own stored currency field, which was hardcoded to
     * Currency.KES unconditionally before an earlier fix this session,
     * regardless of whether the actual commissionAmount was ever
     * genuinely KES-denominated.
     *
     * COMMISSION_TRANSFER: NEVER converted — always genuinely USD.
     * COMMISSION_DISBURSEMENT: uses the SOURCE Disbursement's own
     * currency field (via disbursementsById) instead of this entry's —
     * that reliably describes what actually determined this commission
     * figure's real magnitude at recording time. Falls back to this
     * entry's own currency if the source can't be found.
     * Any other type: trusts this entry's own currency field directly.
     */
    private BigDecimal ledgerEntryAmountUsd(CompanyLedgerEntry e, Map<String, Disbursement> disbursementsById) {
        BigDecimal amount = e.getAmount();
        if (amount == null) return BigDecimal.ZERO;

        if ("COMMISSION_TRANSFER".equals(e.getType())) {
            return amount;
        }

        if ("COMMISSION_DISBURSEMENT".equals(e.getType())) {
            Disbursement source = e.getSourceId() != null ? disbursementsById.get(e.getSourceId()) : null;
            if (source != null) {
                return toUsd(amount, source.getCurrency());
            }
        }

        return toUsd(amount, e.getCurrency() != null ? e.getCurrency().name() : null);
    }

    /**
     * Converts a native-currency figure to USD using the LIVE, current
     * exchange rate — not the rate that was actually applied when the
     * original record was created (not preserved for these fields the
     * way Disbursement.totalDebitedUsd locks in the initiation-time
     * rate). An approximation for historical non-USD data, not a
     * byte-for-byte reconstruction — the best available given what's
     * actually stored.
     */
    private BigDecimal toUsd(BigDecimal amount, String currencyCode) {
        if (amount == null) return BigDecimal.ZERO;
        if (currencyCode == null || "USD".equalsIgnoreCase(currencyCode)) return amount;
        BigDecimal rate = exchangeRateService.getRate(currencyCode.toUpperCase(), "USD");
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}