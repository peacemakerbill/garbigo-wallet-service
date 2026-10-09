package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class WalletStatementRequest {

    @NotNull
    private LocalDate fromDate;

    private LocalDate toDate;

    private String type; // Optional filter: DEPOSIT, WITHDRAWAL, etc.
}