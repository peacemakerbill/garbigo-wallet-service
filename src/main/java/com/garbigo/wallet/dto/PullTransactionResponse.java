package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Shared response for both Register Pull (registeredShortCode populated,
 * transactions/recovered/duplicates left null) and Query Pull Transaction +
 * reconciliation (transactions populated, recovered/duplicates counted).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PullTransactionResponse {
    private boolean success;
    private String message;
    private String responseRefId;
    private String registeredShortCode;
    private List<PullTransactionRecord> transactions;
    /** Number of pulled transactions that had no matching local record and were credited to a wallet. */
    private Integer recovered;
    /** Number of pulled transactions already present locally (existsByProviderReference) — skipped. */
    private Integer duplicates;
    /** Number of pulled transactions whose billreference didn't match any wallet — could not be credited. */
    private Integer unmatched;
}