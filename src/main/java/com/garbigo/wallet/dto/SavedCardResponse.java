package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SavedCardResponse {
    private String paymentMethodId;
    private String brand;
    private String last4;
    private boolean isDefault;
}