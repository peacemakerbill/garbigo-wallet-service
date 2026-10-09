package com.premisave.wallet.service;

import com.premisave.wallet.config.CommissionConfig;
import com.premisave.wallet.entity.CompanyLedgerEntry;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.repository.CompanyLedgerRepository;
import com.premisave.wallet.util.DateRangeCriteriaUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Computes and records commission the company takes as a cut of user
 * money movements — shared by TransferService (internalTransferRate) and,
 * eventually, each provider-specific disbursement service (gatewayRate).
 * Centralized here rather than duplicated per caller, since the actual
 * math and the CompanyLedgerEntry shape are identical regardless of
 * which rate or which source triggered it.
 *
 * ADDED ON TOP, confirmed explicitly: the commission is charged in
 * addition to the stated transfer/withdrawal amount — the
 * sender/withdrawer pays amount + commission, while the recipient (or,
 * for a disbursement, the external gateway destination) receives the
 * full, unaffected, originally-requested amount. This is why every
 * caller needs to debit amount.add(commission) from the payer's wallet
 * while still sending/crediting the original, unmodified amount
 * everywhere else.
 *
 * NOT credited to any real company wallet — confirmed explicitly. This
 * only ever writes a CompanyLedgerEntry for reporting; no Wallet balance
 * is touched by this class at all.
 */
@Service
@RequiredArgsConstructor
public class CommissionService {

    private final CommissionConfig commissionConfig;
    private final CompanyLedgerRepository companyLedgerRepository;
    private final MongoTemplate mongoTemplate;
    private final ExchangeRateService exchangeRateService;

    public BigDecimal getInternalTransferRate() {
        return commissionConfig.getInternalTransferRate();
    }

    public BigDecimal getGatewayRate() {
        return commissionConfig.getGatewayRate();
    }

    public BigDecimal calculateInternalTransferCommission(BigDecimal amount) {
        return calculate(amount, commissionConfig.getInternalTransferRate());
    }

    public BigDecimal calculateGatewayCommission(BigDecimal amount) {
        return calculate(amount, commissionConfig.getGatewayRate());
    }

    private BigDecimal calculate(BigDecimal amount, BigDecimal rate) {
        if (rate == null) {
            throw new IllegalStateException(
                    "Commission rate is not configured — check commission.internal-transfer-rate / "
                            + "commission.gateway-rate in application.yml");
        }
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Records a commission entry in the company ledger. Does NOT touch
     * any Wallet balance — purely a reporting record. Call this AFTER the
     * actual money movement has already succeeded, same "record only
     * once the real event happened" ordering already used for every
     * other entity in this codebase.
     *
     * currency: the ledger entry's own currency was previously hardcoded
     * to Currency.KES unconditionally, regardless of caller -- wrong for
     * both real callers. TransferService's commission is always computed
     * from the sender's USD wallet balance, so it's always genuinely USD.
     * recordGatewayCommissionFromDisbursement's commission is derived
     * from Disbursement.totalDebited/amount, which are the NATIVE payout
     * fields (confirmed from Disbursement.java's own field docs) -- KES
     * for M-Pesa, USD for Stripe/PayPal/NOWPayments, whatever Flutterwave's
     * destinationCurrency was -- so it genuinely varies by provider and
     * must be supplied by the caller, not assumed here.
     */
    public void recordCommission(String type, BigDecimal commissionAmount, BigDecimal rate, BigDecimal grossAmount,
                                  String sourceType, String sourceId, String sourceReference, String userId,
                                  String description, Currency currency) {
        CompanyLedgerEntry entry = new CompanyLedgerEntry();
        entry.setAmount(commissionAmount);
        entry.setCurrency(currency);
        entry.setType(type);
        entry.setDescription(description);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setSourceReference(sourceReference);
        entry.setRateApplied(rate);
        entry.setGrossAmount(grossAmount);
        entry.setUserId(userId);
        companyLedgerRepository.save(entry);
    }

    /**
     * Convenience wrapper for recording gateway commission from a
     * confirmed Disbursement — derives the commission amount from
     * totalDebited minus amount rather than requiring each of the five
     * provider-specific completeXDisbursement methods to separately
     * extract and pass the same fields. No-ops cleanly for a
     * Disbursement that never had commission computed at all (admin
     * B2B/B2C top-up, which deliberately don't carry it — see
     * MpesaDisbursementService — or any Disbursement created before this
     * field existed), rather than throwing on a null totalDebited.
     *
     * The commission's currency is d.getCurrency() itself, mapped to the
     * ledger's Currency enum -- since totalDebited/amount are both native
     * payout fields (see recordCommission's javadoc above). d.getCurrency()
     * is confirmed safe for KES (M-Pesa) and USD (Stripe/PayPal/NOWPayments)
     * -- both verified enum values used throughout this codebase. It is
     * NOT confirmed safe for every possible Flutterwave destinationCurrency
     * (dozens of African currency codes), since Currency.java itself was
     * never shared/verified this session -- guessing at its full value set
     * would risk silently mis-mapping an unsupported currency rather than
     * surfacing the gap. That edge case is deliberately left unhandled
     * here (Currency.valueOf will throw IllegalArgumentException for an
     * unsupported code) rather than papered over with an assumed fallback.
     */
    public void recordGatewayCommissionFromDisbursement(Disbursement d) {
        if (d.getTotalDebited() == null || d.getCommissionRate() == null) {
            return;
        }
        BigDecimal commission = d.getTotalDebited().subtract(d.getAmount());
        if (commission.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        recordCommission("COMMISSION_DISBURSEMENT", commission, d.getCommissionRate(), d.getAmount(),
                "DISBURSEMENT", d.getId(), d.getReference(), d.getUserId(),
                "Commission on " + d.getProvider() + " disbursement to " + d.getDestination(),
                Currency.valueOf(d.getCurrency()));
    }

    /**
     * Admin-only: every ledger entry, paginated — see AdminFinanceController.
     * This is the only way to actually see the P&L data recorded by every
     * transfer/disbursement commission until now — CompanyLedgerRepository
     * has existed since Stage 1, but nothing exposed it through the API at
     * all before this.
     */
    public Page<CompanyLedgerEntry> getAllLedgerEntries(Pageable pageable) {
        return getAllLedgerEntries(null, null, null, null, pageable);
    }

    /**
     * Filtered version — userId/type/date-range all optional. Same
     * dynamic-Criteria approach as DepositService/DisbursementService's
     * own admin filtering: starts from an empty Criteria(), genuinely
     * paginated with an accurate total count via a separate
     * mongoTemplate.count call. type is the ledger's primary
     * categorization dimension (COMMISSION_TRANSFER,
     * COMMISSION_DISBURSEMENT, COMPANY_DISBURSEMENT, etc.) — the natural
     * equivalent of "status" on the other admin-filtered endpoints. With
     * every filter left null, produces the identical result set the
     * unfiltered overload above always has.
     */
    public Page<CompanyLedgerEntry> getAllLedgerEntries(String userId, String type, LocalDate fromDate,
                                                         LocalDate toDate, Pageable pageable) {
        Criteria criteria = new Criteria();
        if (userId != null && !userId.isBlank()) {
            criteria = criteria.and("userId").is(userId);
        }
        if (type != null && !type.isBlank()) {
            criteria = criteria.and("type").is(type.toUpperCase());
        }
        criteria = DateRangeCriteriaUtil.applyDateRange(criteria, "createdAt", fromDate, toDate);

        long total = mongoTemplate.count(new Query(criteria), CompanyLedgerEntry.class);
        Query pagedQuery = new Query(criteria).with(pageable);
        List<CompanyLedgerEntry> content = mongoTemplate.find(pagedQuery, CompanyLedgerEntry.class);

        normalizeToUsd(content);

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Mutates each entry's amount/currency to USD in place, in memory
     * only — never re-saved, so the actual stored DB record is
     * untouched. Explicit request: "the reports should also be showing
     * data in USD... if there were any records saved in different
     * currencies before, conversion should take place."
     *
     * "Intelligence" here means NOT blindly trusting each entry's own
     * stored currency field, which was hardcoded to Currency.KES
     * unconditionally before an earlier fix this session — regardless of
     * whether the actual commissionAmount was ever genuinely
     * KES-denominated:
     *
     * COMMISSION_TRANSFER entries are NEVER converted — always genuinely
     * USD (commission is computed from a Transfer's amount, which
     * TransferService always computes directly against the sender's USD
     * wallet balance), regardless of what this entry's own currency
     * field says. Converting based on a stale "KES" label here would
     * introduce a NEW error (shrinking an already-correct USD figure by
     * the exchange rate), not fix one.
     *
     * COMMISSION_DISBURSEMENT entries look up the SOURCE Disbursement
     * (via sourceId) and use ITS OWN currency field instead of this
     * entry's — the source Disbursement's currency reliably describes
     * what the underlying totalDebited/amount were actually denominated
     * in, which is what genuinely determined this commission figure's
     * real magnitude at recording time; this entry's own currency field
     * does not reliably reflect that for anything recorded before the
     * recordGatewayCommissionFromDisbursement fix. Falls back to this
     * entry's own currency if the source Disbursement can't be found
     * (deleted, or genuinely missing).
     *
     * Any other type (COMPANY_DISBURSEMENT, future direct-revenue types)
     * trusts this entry's own currency field directly — COMPANY_DISBURSEMENT
     * has always explicitly written Currency.USD since it was introduced.
     *
     * This is necessarily an APPROXIMATION for historical non-USD
     * figures: converted using TODAY's live rate, not the rate actually
     * in effect when the entry was recorded (not preserved on
     * CompanyLedgerEntry itself) — the best available given what's
     * actually stored, not a byte-for-byte historical reconstruction.
     */
    private void normalizeToUsd(List<CompanyLedgerEntry> entries) {
        List<String> disbursementSourceIds = entries.stream()
                .filter(e -> "COMMISSION_DISBURSEMENT".equals(e.getType()) && e.getSourceId() != null)
                .map(CompanyLedgerEntry::getSourceId)
                .distinct()
                .toList();
        Map<String, Disbursement> disbursementsById = disbursementSourceIds.isEmpty()
                ? Map.of()
                : mongoTemplate.find(new Query(Criteria.where("_id").in(disbursementSourceIds)), Disbursement.class)
                        .stream().collect(Collectors.toMap(Disbursement::getId, d -> d));

        for (CompanyLedgerEntry e : entries) {
            if (e.getAmount() == null) {
                continue;
            }
            if ("COMMISSION_TRANSFER".equals(e.getType())) {
                e.setCurrency(Currency.USD);
                continue;
            }
            if ("COMMISSION_DISBURSEMENT".equals(e.getType())) {
                Disbursement source = e.getSourceId() != null ? disbursementsById.get(e.getSourceId()) : null;
                if (source != null) {
                    e.setAmount(toUsd(e.getAmount(), source.getCurrency()));
                    e.setCurrency(Currency.USD);
                    continue;
                }
            }
            e.setAmount(toUsd(e.getAmount(), e.getCurrency() != null ? e.getCurrency().name() : null));
            e.setCurrency(Currency.USD);
        }
    }

    private BigDecimal toUsd(BigDecimal amount, String currencyCode) {
        if (amount == null) return BigDecimal.ZERO;
        if (currencyCode == null || "USD".equalsIgnoreCase(currencyCode)) return amount;
        BigDecimal rate = exchangeRateService.getRate(currencyCode.toUpperCase(), "USD");
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}