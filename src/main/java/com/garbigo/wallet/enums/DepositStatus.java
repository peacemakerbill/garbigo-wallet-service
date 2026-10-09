package com.premisave.wallet.enums;

/**
 * Deposit lifecycle status — deliberately its own enum, not a reuse of
 * TransactionStatus, mirroring how Disbursement uses its own
 * DisbursementStatus rather than TransactionStatus even though the
 * underlying concept (pending / succeeded / failed) is the same. Keeps
 * Deposit's terminology consistent with Disbursement's (SUCCESS, not
 * COMPLETED) rather than mixing two different vocabularies across two
 * conceptually parallel entities.
 */
public enum DepositStatus {
    PENDING,
    SUCCESS,
    FAILED
}