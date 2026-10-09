package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.ExchangeRateEntry;
import com.premisave.wallet.entity.ExchangeRate;
import com.premisave.wallet.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Live-fetch and read access to the persisted exchange rates
 * ExchangeRateService caches in Redis and refreshes automatically on a
 * schedule (see that class's own javadoc). Same ADMIN/FINANCE/OPERATIONS
 * security posture as every other admin controller in this codebase.
 *
 * The manual add/update endpoint (POST with a typed-in array) was
 * removed on request — every saved rate now comes either from
 * Frankfurter directly (via /fetch below) or from the scheduled
 * background refresh, never a manually-entered number. saveOrUpdateRates
 * itself still exists in ExchangeRateService (now private) — it's the
 * shared persistence logic /fetch and the scheduled refresh both still
 * call internally — only the public, manually-typed-payload endpoint is
 * gone.
 */
@RestController
@RequestMapping("/admin/exchange-rates")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE', 'OPERATIONS')")
public class AdminExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    /**
     * Fetches EVERY available rate for the given base currency directly
     * from Frankfurter in one call and saves all of them — the array
     * comes from Frankfurter itself, no manual entry possible anymore.
     * No request body — base is a query parameter.
     */
    @PostMapping("/fetch")
    public ResponseEntity<ApiResponse<List<ExchangeRateEntry>>> fetchFromFrankfurter(@RequestParam String base) {
        List<ExchangeRateEntry> saved = exchangeRateService.fetchAndSaveAllRates(base);
        return ResponseEntity.ok(ApiResponse.success(
                "Fetched and saved " + saved.size() + " rate(s) from Frankfurter for base=" + base, saved));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ExchangeRate>>> getAllRates() {
        return ResponseEntity.ok(ApiResponse.success("Exchange rates retrieved", exchangeRateService.getAllRates()));
    }
}