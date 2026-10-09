package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Enhanced Withdraw Request
 */
@Data
public class WithdrawRequest {

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "Destination is required")
    private String destination;

    private String provider = "MPESA";

    private String remarks;

    /**
     * Reference for idempotency
     */
    private String reference;
}