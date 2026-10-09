package com.premisave.wallet.entity;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A single gateway balance check, persisted every time one happens — never
 * overwritten, same history-preserving pattern as Deposit/Disbursement/
 * Transfer/Payment/ManualAdjustment throughout this session. "Latest
 * balance per provider" is a query against this collection (see
 * GatewayBalanceSnapshotRepository.findFirstByProviderOrderByCreatedAtDesc),
 * not a separately-maintained single row — this gives both a genuine
 * history/trend over time AND a cheap "what's the current state" view
 * from the same data.
 *
 * balances deliberately mirrors ProviderBalanceResponse.CurrencyBalance's
 * shape (currency + a flexible amounts map) rather than reusing that DTO
 * class directly — same reasoning as ManualAdjustmentRecordResponse
 * mirroring ManualAdjustment rather than crossing the dto/entity boundary.
 */
@Data
@Document(collection = "gateway_balance_snapshots")
public class GatewayBalanceSnapshot {

    @Id
    private String id;

    @Indexed
    private String provider;

    /** "AVAILABLE", "PENDING_ASYNC" (M-Pesa only), or "ERROR" — same vocabulary as ProviderBalanceResponse.status. */
    private String status;

    private List<CurrencyBalanceEntry> balances;
    private String message;

    /** M-Pesa only — null for every other provider. */
    private String conversationId;
    private String originatorConversationId;

    /** Which admin triggered this check — resolved from the caller's own JWT, never taken from a request body. */
    private String checkedBy;

    @CreatedDate
    private LocalDateTime createdAt;

    @Data
    public static class CurrencyBalanceEntry {
        private String currency;
        private Map<String, BigDecimal> amounts;
    }
}