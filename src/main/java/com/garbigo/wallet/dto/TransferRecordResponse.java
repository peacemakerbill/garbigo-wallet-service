package com.premisave.wallet.dto;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransferStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single Transfer record for history display. Includes a computed
 * `direction` field ("SENT" or "RECEIVED") — since GET /wallet/transfer/history
 * returns transfers where the requesting user is EITHER the sender or the
 * recipient (see TransferRepository.findBySenderIdOrRecipientIdOrderByCreatedAtDesc),
 * a UI needs some way to tell which side of each record it's looking at
 * without re-deriving it itself by comparing IDs.
 */
@Data
public class TransferRecordResponse {
    private String id;

    private String senderId;
    private String senderEmail;
    private String recipientId;
    private String recipientEmail;

    /** "SENT" if the requesting user is the sender, "RECEIVED" if the recipient — computed at mapping time, not stored on Transfer itself. */
    private String direction;

    private BigDecimal amount;
    private BigDecimal totalDebited;
    private Currency currency;
    private String description;
    private TransferStatus status;
    private String reference;
    private String failureReason;
    private String initiatedBy;
    private LocalDateTime createdAt;
}
