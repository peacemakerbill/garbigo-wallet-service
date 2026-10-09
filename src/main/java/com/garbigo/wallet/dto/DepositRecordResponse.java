package com.premisave.wallet.dto;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DepositStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single Deposit record for history display — distinct from
 * PaymentResponse's role (the generic id/success/message shape returned
 * when a deposit is first initiated). This is what GET /deposits/history
 * returns: the full, already-resolved record.
 */
@Data
public class DepositRecordResponse {
    private String id;
    private String userId;
    private BigDecimal amount;
    private Currency currency;
    private String provider;
    private String channel;
    private String source;
    private DepositStatus status;
    private String reference;
    private String providerReference;
    private String failureReason;

    // NOWPayments-specific — null for every other provider.
    private String payAddress;
    private String payAmount;
    private String payCurrency;
    private BigDecimal priceAmount;
    private String priceCurrency;

    private LocalDateTime createdAt;
}