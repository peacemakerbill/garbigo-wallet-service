package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@AllArgsConstructor
public class WalletStatementResponse {

    private String accountNumber;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private BigDecimal totalCredits;
    private BigDecimal totalDebits;
    private LocalDate fromDate;
    private LocalDate toDate;
    private List<TransactionResponse> transactions;
}