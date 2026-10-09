package com.premisave.wallet.entity;

import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.enums.MpesaOperationType;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Tracks the lifecycle of the "operational" M-Pesa async APIs: Account
 * Balance, Transaction Status, Reversal, and B2Pochi. These don't fit the
 * Disbursement/Transaction model (no single user wallet is always involved —
 * e.g. Account Balance is purely informational), so they get their own
 * lightweight record, reconciled the same way as B2C/B2B: PENDING until
 * Safaricom's ResultURL callback arrives, keyed by ConversationID.
 *
 * Status reuses DisbursementStatus (PENDING/SUCCESS/FAILED) — same three
 * states apply here, no need for a separate enum.
 */
@Data
@Document(collection = "mpesa_operations")
public class MpesaOperation {

    @Id
    private String id;

    private MpesaOperationType type;

    /** Admin/finance userId (or "system" for scheduled operations) that triggered this. */
    private String initiatedBy;

    /** Our own request identifier, if we generated one (e.g. B2Pochi's OriginatorConversationID). */
    private String originatorConversationId;

    /**
     * Safaricom's ConversationID — primary key for matching the async ResultURL
     * callback. sparse=true because outright-rejected requests never get one
     * (left null) — without sparse, multiple nulls would violate the unique index.
     */
    @Indexed(unique = true, sparse = true)
    private String conversationId;

    private DisbursementStatus status = DisbursementStatus.PENDING;

    /** Free-form summary of what was requested — e.g. transactionId, amount, phone. */
    private Map<String, Object> requestSummary;

    /** Populated once the callback arrives. */
    private String resultCode;
    private String resultDesc;
    private Map<String, Object> resultData;

    /**
     * For REVERSAL only — the id of the original Transaction (deposit) being
     * reversed, if one was found by providerReference. Lets the wallet be
     * debited automatically once the reversal succeeds.
     */
    private String relatedTransactionId;

    @CreatedDate
    private LocalDateTime createdAt;
}