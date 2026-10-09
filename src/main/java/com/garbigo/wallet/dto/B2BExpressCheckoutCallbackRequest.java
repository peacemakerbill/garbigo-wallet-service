package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Matches the B2B Express Checkout (USSD Push to Till) result callback —
 * flatter than the standard B2C/B2B "Result" envelope used elsewhere in
 * this service.
 *
 * Cancelled example:
 * {"resultCode":"4001","resultDesc":"User cancelled transaction",
 *  "requestId":"...","amount":"71.0","paymentReference":"..."}
 *
 * Successful example:
 * {"resultCode":"0","resultDesc":"...","amount":"71.0","requestId":"...",
 *  "resultType":"0","conversationID":"...","transactionId":"...","status":"SUCCESS"}
 */
@Data
public class B2BExpressCheckoutCallbackRequest {

    @JsonProperty("resultCode")
    private String resultCode;

    @JsonProperty("resultDesc")
    private String resultDesc;

    @JsonProperty("requestId")
    private String requestId;

    @JsonProperty("amount")
    private String amount;

    @JsonProperty("paymentReference")
    private String paymentReference;

    @JsonProperty("resultType")
    private String resultType;

    @JsonProperty("conversationID")
    private String conversationID;

    @JsonProperty("transactionId")
    private String transactionId;

    @JsonProperty("status")
    private String status;
}