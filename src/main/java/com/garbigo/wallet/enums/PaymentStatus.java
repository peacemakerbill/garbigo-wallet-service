package com.premisave.wallet.enums;

/**
 * Payment lifecycle status — mirrors DepositStatus/DisbursementStatus/
 * TransferStatus's shape, not a reuse of TransactionStatus.
 */
public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED
}