package com.premisave.wallet.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaypalPayoutRequest {
    private BigDecimal amount;
    private String recipientEmail;
}