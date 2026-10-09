package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DisbursementResponse {
    private String disbursementId;
    private String status;
    private String message;
}