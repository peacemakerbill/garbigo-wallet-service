package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Body for POST /internal/transfer — a transfer triggered by another
 * Premisave service (e.g. property-service), not a logged-in user.
 *
 * Requires senderUserId explicitly, unlike the user-facing TransferRequest
 * — InternalApiKeyFilter authenticates the CALLING SERVICE, not an end
 * user, so there's no JWT/userId to resolve a sender from the way
 * WalletController.resolveUserId does for every other endpoint.
 */
@Data
public class InternalTransferRequest {

    @NotBlank(message = "senderUserId is required")
    private String senderUserId;

    @NotBlank(message = "Recipient account number (email) is required")
    private String recipientAccountNumber;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;

    private String description;

    private String reference;

    /**
     * Which service initiated this transfer (e.g. "PROPERTY_SERVICE") —
     * stored on Transfer.initiatedBy for audit purposes. Required, unlike
     * the user-facing endpoint where this is always just "USER" and never
     * needs stating explicitly.
     */
    @NotBlank(message = "initiatedBy is required — identify which service is calling this endpoint")
    private String initiatedBy;
}