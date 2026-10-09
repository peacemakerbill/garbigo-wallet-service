package com.premisave.wallet.dto;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.PaymentStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single Payment record for history display — distinct from
 * PaymentResponse's role (the generic id/success/message shape returned
 * when a payment is first initiated). This is what GET /payments/history
 * returns: the full, already-resolved record.
 */
@Data
public class PaymentRecordResponse {
    private String id;
    private String userId;
    private BigDecimal amount;
    private Currency currency;
    private String service;
    private String description;
    private PaymentStatus status;
    private String reference;
    private String failureReason;
    private String initiatedBy;
    private String email;
    private LocalDateTime createdAt;
}