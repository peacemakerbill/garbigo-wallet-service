package com.premisave.wallet.util;

import org.springframework.data.mongodb.core.query.Criteria;

import java.time.LocalDate;

/**
 * Shared helper for applying an optional [fromDate, toDate] range filter to
 * a MongoDB Criteria — used identically across DepositService/
 * DisbursementService/TransferService/PaymentService's history filtering,
 * avoiding the same date-range logic duplicated four times.
 *
 * fromDate is inclusive from the start of that day; toDate is inclusive
 * through the end of that day (23:59:59) — a query using the same date
 * for both fromDate and toDate should return everything that happened
 * that day, not just events at exactly midnight.
 */
public final class DateRangeCriteriaUtil {

    private DateRangeCriteriaUtil() {}

    public static Criteria applyDateRange(Criteria criteria, String field, LocalDate fromDate, LocalDate toDate) {
        if (fromDate != null && toDate != null) {
            return criteria.and(field).gte(fromDate.atStartOfDay()).lte(toDate.atTime(23, 59, 59));
        } else if (fromDate != null) {
            return criteria.and(field).gte(fromDate.atStartOfDay());
        } else if (toDate != null) {
            return criteria.and(field).lte(toDate.atTime(23, 59, 59));
        }
        return criteria;
    }
}