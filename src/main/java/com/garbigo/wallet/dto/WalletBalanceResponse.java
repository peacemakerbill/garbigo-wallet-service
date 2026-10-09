package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class WalletBalanceResponse {
    private BigDecimal balance;
    private String currency;
    private boolean isFrozen;
}