package com.premisave.wallet.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MpesaB2CRequest {
    private String phoneNumber;
    private BigDecimal amount;
    private String remarks = "Premisave Wallet Disbursement";
}