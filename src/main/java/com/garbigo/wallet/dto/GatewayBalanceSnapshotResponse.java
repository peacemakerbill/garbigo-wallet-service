package com.premisave.wallet.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** A single saved gateway balance check, as viewed from the database rather than a live provider call. */
@Data
public class GatewayBalanceSnapshotResponse {
    private String provider;
    private String status;
    private List<ProviderBalanceResponse.CurrencyBalance> balances;
    private String message;
    private String conversationId;
    private String originatorConversationId;
    private String checkedBy;
    private LocalDateTime lastUpdated;
}