package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Matches Safaricom's per-transaction fields from the Pull Transaction Query
 * response. Field names are lowercase per the official spec (transactionId
 * is the one exception — everything else is lowercase, unlike every other
 * M-Pesa API in this codebase, which uses PascalCase).
 * See https://developer.safaricom.co.ke/apis/PullTransaction
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) added after a real sandbox
 * query confirmed Safaricom also sends a "sender" field ("sender":"MPESA")
 * that isn't mapped here — without this, MpesaService.parsePullTransactionsResponse's
 * objectMapper.treeToValue(...) throws UnrecognizedPropertyException on
 * every single record, since MpesaService's ObjectMapper is a standalone
 * instance (not the Spring-managed bean), so it doesn't inherit Spring
 * Boot's usual lenient fail-on-unknown-properties:false default — it's
 * vanilla Jackson, where that setting defaults to true. Every other
 * M-Pesa result DTO in this codebase already carries this annotation for
 * the same reason.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PullTransactionRecord {

    @JsonProperty("transactionId")
    private String transactionId;

    @JsonProperty("trxDate")
    private String trxDate;

    @JsonProperty("msisdn")
    private String msisdn;

    @JsonProperty("transactiontype")
    private String transactionType;

    @JsonProperty("billreference")
    private String billReference;

    @JsonProperty("amount")
    private String amount;

    @JsonProperty("organizationname")
    private String organizationName;
}