package com.garbigo.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class B2BExpressCheckoutResponse {
    private boolean success;
    private String requestRefId;
    private String message;
}