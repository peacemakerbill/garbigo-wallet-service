package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Safaricom C2B callback payload (both validation and confirmation use the same shape).
 *
 * Example payload:
 * {
 *   "TransactionType": "Pay Bill",
 *   "TransID": "RCA71X5MJ4",
 *   "TransTime": "20191122063845",
 *   "TransAmount": "10.00",
 *   "BusinessShortCode": "600638",
 *   "BillRefNumber": "user@example.com",   ← customer's email
 *   "InvoiceNumber": "",
 *   "OrgAccountBalance": "49197.00",
 *   "ThirdPartyTransID": "",
 *   "MSISDN": "254708374149",
 *   "FirstName": "John",
 *   "MiddleName": "J.",
 *   "LastName": "Doe"
 * }
 */
@Data
public class MpesaC2BCallbackRequest {

    @JsonProperty("TransactionType")
    private String transactionType;

    @JsonProperty("TransID")
    private String transID;

    @JsonProperty("TransTime")
    private String transTime;

    @JsonProperty("TransAmount")
    private String transAmount;

    @JsonProperty("BusinessShortCode")
    private String businessShortCode;

    /** The account number the customer entered — we use their email here. */
    @JsonProperty("BillRefNumber")
    private String billRefNumber;

    @JsonProperty("InvoiceNumber")
    private String invoiceNumber;

    @JsonProperty("OrgAccountBalance")
    private String orgAccountBalance;

    @JsonProperty("ThirdPartyTransID")
    private String thirdPartyTransID;

    @JsonProperty("MSISDN")
    private String MSISDN;

    @JsonProperty("FirstName")
    private String firstName;

    @JsonProperty("MiddleName")
    private String middleName;

    @JsonProperty("LastName")
    private String lastName;
}