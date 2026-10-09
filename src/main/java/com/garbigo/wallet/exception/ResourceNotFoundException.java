package com.premisave.wallet.exception;

/**
 * Generic "this id doesn't exist" exception — for a lookup-by-id that
 * genuinely fails because the caller supplied a wrong or made-up id, not
 * a system fault. Deliberately generic rather than a dedicated
 * DepositNotFoundException/DisbursementNotFoundException/etc. per
 * entity — one exception type, message states which entity, so a single
 * clean handler in GlobalExceptionHandler covers every "not found" case
 * across the whole app, including any future one, rather than needing a
 * new dedicated handler every time another entity gets a lookup-by-id
 * method.
 *
 * Replaces the bare `new RuntimeException("X not found: " + id)` pattern
 * used across the reconciliation feature (and pre-existing elsewhere,
 * e.g. AdminWalletService.getTransactionById) — a plain RuntimeException
 * has no dedicated handler, so it fell through to the generic catch-all,
 * surfacing as a raw 500 "An unexpected error occurred" with a full
 * stack trace for what is genuinely just a wrong id, confirmed directly
 * from real testing.
 */
@SuppressWarnings("serial")
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}