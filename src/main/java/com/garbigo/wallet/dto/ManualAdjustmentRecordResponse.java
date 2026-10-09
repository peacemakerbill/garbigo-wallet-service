package com.premisave.wallet.dto;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.ManualAdjustmentType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A single manual adjustment record for history/audit display. */
@Data
public class ManualAdjustmentRecordResponse {
    private String id;
    private String userId;
    private String accountNumber;
    private ManualAdjustmentType type;
    private BigDecimal amount;
    private Currency currency;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String reason;
    private String reference;
    private String performedBy;
    private LocalDateTime createdAt;
}