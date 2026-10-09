package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic synchronous acknowledgement shape shared by AccountBalance,
 * TransactionStatus, Reversal, and B2Pochi — all four just return
 * {ResponseCode, ResponseDescription, ConversationID, OriginatorConversationID}
 * on acceptance; the real outcome always arrives later via ResultURL.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MpesaAsyncResponse {
    private boolean success;
    private String message;
    private String conversationId;
    private String originatorConversationId;
}