package com.premisave.wallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * refreshIntervalMs isn't actually read from here at runtime — @Scheduled
 * needs a literal property-placeholder string
 * (fixedDelayString = "${exchange-rate.refresh-interval-ms:1200000}"),
 * not a value pulled from a @ConfigurationProperties bean at
 * annotation-processing time. It's still declared here so the property
 * path and its default are documented in one place, matching every
 * other config class's own convention (MpesaConfig, CommissionConfig,
 * etc.) — the yml value itself is the actual source of truth either way.
 */
@Data
@Component
@ConfigurationProperties(prefix = "exchange-rate")
public class ExchangeRateConfig {

    /** Default 20 minutes (1,200,000 ms) — actually enforced via the @Scheduled annotation's own placeholder default, kept in sync here. */
    private long refreshIntervalMs = 20 * 60 * 1000L;

    /** How many attempts per currency pair before giving up on that pair for this refresh cycle. */
    private int maxRetries = 3;
}