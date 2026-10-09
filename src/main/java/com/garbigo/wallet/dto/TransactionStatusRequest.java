package com.premisave.wallet.dto;

import lombok.Data;

/**
 * At least one of transactionId or originalConversationId must be provided —
 * validated in MpesaOperationsService rather than via annotations, since
 * "either/or" isn't expressible with simple bean validation.
 */
@Data
public class TransactionStatusRequest {

    /** M-Pesa Receipt Number of the transaction being queried. */
    private String transactionId;

    /**
     * Alternative to transactionId — the OriginalConversationID from the
     * original request's response. Named to match Safaricom's own
     * OriginalConversationID field exactly (rather than "originator...")
     * so it isn't confused with OriginatorConversationID — the ID Safaricom
     * assigns to THIS status-check request itself, which is a different
     * value entirely (see MpesaAsyncResponse.originatorConversationId).
     */
    private String originalConversationId;

    private String remarks;

    private String occasion;
}