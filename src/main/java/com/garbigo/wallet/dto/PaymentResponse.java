package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PaymentResponse {
    private boolean success;
    private String transactionId;
    private String message;
}