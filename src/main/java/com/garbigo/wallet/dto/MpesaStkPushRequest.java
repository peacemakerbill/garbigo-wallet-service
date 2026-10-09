package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MpesaStkPushRequest {
    @NotBlank
    private String phoneNumber;

    @NotNull
    private BigDecimal amount;

    private String accountReference;
}