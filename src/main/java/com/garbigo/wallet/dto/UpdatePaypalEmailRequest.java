package com.garbigo.wallet.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdatePaypalEmailRequest {

    @NotBlank
    @Email
    private String paypalEmail;
}