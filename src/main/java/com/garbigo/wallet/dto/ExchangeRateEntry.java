package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One entry in the add/update exchange rates request body — an array of these is accepted per call. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateEntry {
    private String baseCurrency;
    private String quoteCurrency;
    private BigDecimal rate;
}