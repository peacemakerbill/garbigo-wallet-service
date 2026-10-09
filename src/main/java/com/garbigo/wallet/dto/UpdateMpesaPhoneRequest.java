package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateMpesaPhoneRequest {

    /**
     * Accepts 07XXXXXXXX, 01XXXXXXXX, 254XXXXXXXXX, or +254XXXXXXXXX.
     * Normalized to 254XXXXXXXXX on save (see MpesaService.normalizePhone).
     */
    @NotBlank
    @Pattern(
            regexp = "^(?:\\+254|254|0)[17]\\d{8}$",
            message = "Must be a valid Safaricom number, e.g. 07XXXXXXXX or 2547XXXXXXXX"
    )
    private String mpesaPhoneNumber;
}