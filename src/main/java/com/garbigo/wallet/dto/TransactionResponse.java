package com.garbigo.wallet.dto;

import com.garbigo.wallet.enums.TransactionStatus;
import com.garbigo.wallet.enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class TransactionResponse {
    private String id;
    private TransactionType type;
    private TransactionStatus status;
    private BigDecimal amount;
    private String currency;
    private String description;
    private LocalDateTime createdAt;
}