package com.premisave.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Reverses a C2B transaction (customer paid our shortcode in error, or a
 * refund is owed). Per Safaricom's Reversal API, this only supports C2B —
 * B2C payouts cannot be reversed via API and must be handled manually on
 * the M-Pesa portal.
 */
@Data
public class MpesaReversalRequest {

    /** M-Pesa Receipt Number of the transaction being reversed. */
    @NotBlank
    private String transactionId;

    @NotNull
    @DecimalMin("1.00")
    private BigDecimal amount;

    @NotBlank
    private String remarks;

    /** Optional idempotency key — generated if not provided. */
    private String reference;
}