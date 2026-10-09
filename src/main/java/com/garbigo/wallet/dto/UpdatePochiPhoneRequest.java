package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdatePochiPhoneRequest {

    @NotBlank
    private String pochiPhoneNumber;
}