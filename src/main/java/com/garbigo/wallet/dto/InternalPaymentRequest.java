package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Body for POST /internal/payment — a payment triggered by another
 * Premisave service (e.g. property-service deducting a booking fee or
 * ad-subscription charge on a user's behalf), not a logged-in user.
 *
 * Mirrors InternalTransferRequest exactly, same reasoning: requires
 * userId explicitly, since InternalApiKeyFilter authenticates the
 * CALLING SERVICE, not an end user — there's no JWT to resolve a payer
 * from the way WalletController.resolveUserId does everywhere else.
 */
@Data
public class InternalPaymentRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "Service name is required (e.g., booking, subscription)")
    private String service;

    private String description;

    private String reference;

    /**
     * Which service initiated this payment (e.g. "PROPERTY_SERVICE") —
     * stored on Payment.initiatedBy for audit purposes.
     */
    @NotBlank(message = "initiatedBy is required — identify which service is calling this endpoint")
    private String initiatedBy;
}