package com.premisave.wallet.dto;

import lombok.Data;

@Data
public class MpesaCallbackRequest {
    private String transactionType;
    private String transID;
    private String transTime;
    private String transAmount;
    private String businessShortCode;
    private String billRefNumber;
    private String invoiceNumber;
    private String thirdPartyTransID;
    private String MSISDN;
    private String firstName;
}