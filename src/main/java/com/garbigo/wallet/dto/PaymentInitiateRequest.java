package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Enhanced Payment Request with strong idempotency support
 */
@Data
public class PaymentInitiateRequest {

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "Service name is required (e.g., booking, subscription)")
    private String service;

    /**
     * Specific, human-readable detail beyond the category in service
     * above (e.g. "August 2026 ad subscription") — stored on
     * Payment.description. New field; wasn't previously captured at all.
     */
    private String description;

    /**
     * Reference is highly recommended for idempotency
     * Prevents duplicate deductions on retry
     */
    private String reference;
}