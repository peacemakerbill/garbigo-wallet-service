package com.premisave.wallet.enums;

/**
 * Distinguishes the "operational" M-Pesa async flows (account balance,
 * transaction status, reversal, B2Pochi) from user-facing deposits/disbursements.
 * Persisted on MpesaOperation so callbacks can be routed and admins can query
 * past results by ConversationID.
 */
public enum MpesaOperationType {
    ACCOUNT_BALANCE,
    TRANSACTION_STATUS,
    REVERSAL,
    B2C_POCHI
}