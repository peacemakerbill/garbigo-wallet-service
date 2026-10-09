package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Enhanced Transfer Request with better validation and idempotency support
 */
@Data
public class TransferRequest {

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "Recipient account number (email) is required")
    private String recipientAccountNumber; // recipient's email

    /**
     * The reason for this transfer (e.g. "Rent split", "Repaying a
     * friend") — stored on Transfer.description and shown on both the
     * sender's debit and recipient's credit Transaction descriptions.
     *
     * @JsonAlias accepts "reason" or "purpose" as alternate JSON key
     * names, so a caller using either continues to work unchanged —
     * Jackson binds it straight into this same field at deserialization
     * time. If a caller somehow sends more than one of description/
     * reason/purpose in the same request body, Jackson resolves it by
     * JSON key order (last one wins) — not expected to matter in
     * practice, since a well-formed caller would only ever send one.
     */
    @JsonAlias({"reason", "purpose"})
    private String description;

    /**
     * Optional reference for idempotency (recommended for external calls)
     * If not provided, service will generate a UUID
     */
    private String reference;
}