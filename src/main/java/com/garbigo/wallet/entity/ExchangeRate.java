package com.premisave.wallet.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One directional currency pair rate — e.g. baseCurrency="USD",
 * quoteCurrency="KES", rate=130.45 means 1 USD = 130.45 KES, matching
 * FxRateService.getRate's own convention ("how many units of quote
 * equal 1 unit of base").
 *
 * Stored directionally (USD->KES and KES->USD as two separate rows)
 * rather than computing the inverse on read — avoids rounding
 * inconsistencies between a stored rate and its computed inverse, and
 * Currency only has three values (KES/USD/EUR) so the full set of pairs
 * is small (six directional pairs) regardless.
 */
@Data
@Document(collection = "exchange_rates")
public class ExchangeRate {

    @Id
    private String id;

    @Indexed
    private String baseCurrency;

    @Indexed
    private String quoteCurrency;

    /** How many units of quoteCurrency equal 1 unit of baseCurrency. */
    private BigDecimal rate;

    private LocalDateTime updatedAt;
}