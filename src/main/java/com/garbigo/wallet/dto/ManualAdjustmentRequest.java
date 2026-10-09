package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ManualAdjustmentRequest {

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    private BigDecimal amount;

    @NotBlank(message = "Reason is required")
    private String reason;

    /**
     * No longer honored — AdminWalletService.creditWallet/debitWallet now
     * ALWAYS generates the reference server-side ("ADJ-" + a random
     * UUID), regardless of what's sent here. Kept as a field only so an
     * existing caller still sending it in the JSON body continues to
     * deserialize without error; the value itself is ignored. Ad-hoc
     * admin-typed references ("CS-REF-20260627", "ADJ-001") carried no
     * uniqueness guarantee — a UUID does.
     */
    private String reference;
}