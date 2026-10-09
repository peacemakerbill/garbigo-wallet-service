package com.premisave.wallet.enums;

/**
 * Transfer lifecycle status — mirrors DepositStatus/DisbursementStatus's
 * shape (PENDING/SUCCESS/FAILED), not a reuse of TransactionStatus, same
 * reasoning as those two.
 */
public enum TransferStatus {
    PENDING,
    SUCCESS,
    FAILED
}