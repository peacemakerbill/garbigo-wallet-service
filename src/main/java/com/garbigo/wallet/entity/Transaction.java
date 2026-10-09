package com.premisave.wallet.entity;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Document(collection = "transactions")
@CompoundIndexes({
    @CompoundIndex(name = "user_tx_idx", def = "{'userId': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "ref_idx", def = "{'reference': 1}")
})
public class Transaction {

    @Id
    private String id;

    private String userId;
    private String walletId;

    private TransactionType type;
    private TransactionStatus status;

    private BigDecimal amount;
    private Currency currency;

    private String reference; // idempotency key
    private String description;

    private String providerReference; // M-Pesa, PayPal, etc.

    @CreatedDate
    private LocalDateTime createdAt;
}