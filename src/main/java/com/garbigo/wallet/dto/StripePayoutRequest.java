package com.premisave.wallet.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StripePayoutRequest {
    private BigDecimal amount;
    private String destinationAccount;
}