package com.garbigo.wallet.service;

import com.garbigo.wallet.config.ExchangeRateConfig;
import com.garbigo.wallet.dto.ExchangeRateEntry;
import com.garbigo.wallet.entity.ExchangeRate;
import com.garbigo.wallet.repository.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * Caching layer IN FRONT OF FxRateService, not a replacement for it.
 * FxRateService.getRate() makes a real, blocking HTTP call to Frankfurter
 * on every single invocation — no proactive caching there at all, only a
 * 2-minute last-known-good fallback used AFTER a failure. Calling that
 * directly from every M-Pesa/Flutterwave transaction would mean every
 * single money-movement operation waits on a live third-party HTTP call,
 * with a real outage turning into a hard failure across every gateway
 * that needs conversion.
 *
 * SUPPORTS ANY CURRENCY PAIR Frankfurter itself supports — 84 central
 * banks via its v2 API — not a fixed, hand-maintained list. There is
 * deliberately NO hardcoded set of "supported pairs": getRate() below
 * saves a genuinely new pair the first time it's ever requested, and the
 * scheduled refresh then keeps refreshing every pair that has EVER been
 * requested/saved.
 *
 * RESILIENCE: getRate() checks Redis, then MongoDB, and only falls
 * through to a live Frankfurter call if NEITHER has this pair yet. If
 * Frankfurter is genuinely unreachable but a rate was already
 * saved/cached from an earlier successful fetch, that saved rate is used
 * — Frankfurter being down does NOT block a transaction that only needs
 * a pair this system has already seen before. The Redis read itself is
 * wrapped in try/catch: a Redis outage alone must not prevent falling
 * through to the MongoDB check — without that guard, a Redis connection
 * failure would propagate straight out of getRate() and never even reach
 * the DB fallback it's supposed to have.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private final ExchangeRateRepository exchangeRateRepository;
    private final FxRateService fxRateService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ExchangeRateConfig config;

    private static final String CACHE_KEY_PREFIX = "exchange-rate:";

    /** Redis TTL — separate from the refresh interval on purpose: even if a refresh cycle is delayed, cached entries don't expire and silently fall through to a DB read on every single call. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /** Loose sanity check — 3 uppercase letters, matching ISO 4217's own format. Catches an obvious typo before it's saved, WITHOUT maintaining a closed list of "valid" codes. */
    private static final Pattern CURRENCY_CODE_PATTERN = Pattern.compile("^[A-Z]{3}$");

    /**
     * Reads a rate — Redis first, then MongoDB (populating Redis on a DB
     * hit). If this exact pair has genuinely never been requested or
     * saved before, fetches it live from FxRateService ONCE, saves it,
     * and returns it. base==quote short-circuits to 1 without touching
     * Redis/Mongo/Frankfurter at all.
     *
     * A Redis read failure (connection refused, timeout — Redis itself
     * being down, separate from Frankfurter) is caught and logged, not
     * propagated — falls through to the MongoDB check instead, so a
     * Redis outage alone can't take down conversion for a pair that's
     * already safely stored in MongoDB.
     */
    public BigDecimal getRate(String base, String quote) {
        base = validate(base);
        quote = validate(quote);

        if (base.equals(quote)) {
            return BigDecimal.ONE;
        }

        String key = cacheKey(base, quote);

        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return cached instanceof BigDecimal bd ? bd : new BigDecimal(cached.toString());
            }
        } catch (Exception redisFailure) {
            log.warn("The rate cache (Redis) could not be read for {} to {}, so the saved rate is being read from the database instead. Reason: {}",
                    base, quote, redisFailure.getMessage());
        }

        var saved = exchangeRateRepository.findByBaseCurrencyAndQuoteCurrency(base, quote);
        if (saved.isPresent()) {
            cacheInRedisBestEffort(key, saved.get().getRate());
            return saved.get().getRate();
        }

        log.info("First request for {} to {}: no saved rate yet, so it is being fetched live from Frankfurter and saved for next time", base, quote);
        BigDecimal rate = fxRateService.getRate(base, quote);
        saveOrUpdateRates(List.of(new ExchangeRateEntry(base, quote, rate)));
        return rate;
    }

    /**
     * Shared persistence logic — upserts a set of rates into MongoDB and
     * refreshes their Redis cache entries. Called internally by getRate()
     * (seeding a genuinely new pair), fetchAndSaveAllRates() (bulk
     * Frankfurter import), and scheduledRefresh(). Private — no longer
     * exposed as a manual-entry endpoint (removed on request); every
     * saved rate now originates from Frankfurter itself, never a
     * hand-typed number.
     */
    private void saveOrUpdateRates(List<ExchangeRateEntry> entries) {
        for (ExchangeRateEntry entry : entries) {
            String base = validate(entry.getBaseCurrency());
            String quote = validate(entry.getQuoteCurrency());

            ExchangeRate rate = exchangeRateRepository.findByBaseCurrencyAndQuoteCurrency(base, quote)
                    .orElseGet(ExchangeRate::new);
            rate.setBaseCurrency(base);
            rate.setQuoteCurrency(quote);
            rate.setRate(entry.getRate());
            rate.setUpdatedAt(LocalDateTime.now());
            exchangeRateRepository.save(rate);

            cacheInRedisBestEffort(cacheKey(base, quote), entry.getRate());
        }
        log.trace("Saved/updated {} exchange rate(s)", entries.size());
    }

    public List<ExchangeRate> getAllRates() {
        return exchangeRateRepository.findAll();
    }

    /**
     * Fetches EVERY available rate for the given base currency directly
     * from Frankfurter in ONE call (see FxRateService.getAllRates) and
     * saves all of them — the array comes from Frankfurter itself. This
     * is now the ONLY way rates enter the system directly at an admin's
     * request (the manual typed-payload endpoint was removed) — every
     * other rate arrives either through this, or through the scheduled
     * background refresh.
     *
     * Skips any quote currency that doesn't pass the same 3-letter
     * sanity check as everything else here — defensive; shouldn't happen
     * with a genuine Frankfurter response, but guards against silently
     * saving something malformed if the response shape ever changes.
     */
    public List<ExchangeRateEntry> fetchAndSaveAllRates(String base) {
        String validatedBase = validate(base);
        Map<String, BigDecimal> rates = fxRateService.getAllRates(validatedBase);

        List<ExchangeRateEntry> entries = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
            String quote = entry.getKey().toUpperCase();
            if (!CURRENCY_CODE_PATTERN.matcher(quote).matches()) {
                log.warn("Ignored '{}' in the Frankfurter rates for {} because it is not a valid 3-letter currency code",
                        entry.getKey(), validatedBase);
                continue;
            }
            entries.add(new ExchangeRateEntry(validatedBase, quote, entry.getValue()));
        }

        saveOrUpdateRates(entries);
        Map<String, BigDecimal> saved = new LinkedHashMap<>();
        entries.forEach(e -> saved.put(e.getQuoteCurrency(), e.getRate()));
        log.info("Downloaded and saved {} currency rates for {} from Frankfurter. Each value is how much of that currency 1 {} buys: {}",
                entries.size(), currencyLabel(validatedBase), validatedBase, formatRates(saved));
        return entries;
    }

    /**
     * Refreshes EVERY pair that has ever been saved or organically
     * requested — no hardcoded list. Up to config.getMaxRetries()
     * attempts per pair on failure; one pair failing doesn't stop the
     * others from refreshing.
     */
    @Scheduled(fixedDelayString = "${exchange-rate.refresh-interval-ms:1200000}")
    public void scheduledRefresh() {
        List<ExchangeRate> existingPairs = exchangeRateRepository.findAll();
        if (existingPairs.isEmpty()) {
            // Fresh database: nothing to refresh yet, so load every rate for the
            // configured seed bases once. This runs on the first scheduler tick,
            // which fires right at startup, then the normal refresh takes over.
            seedInitialRates();
            existingPairs = exchangeRateRepository.findAll();
            if (existingPairs.isEmpty()) {
                log.info("No currency rates are saved yet and the first download from Frankfurter did not work. Will try again in {} minutes",
                        refreshMinutes());
                return;
            }
            log.info("First start on this database: {} currency rates were downloaded and saved. They will be kept up to date every {} minutes",
                    existingPairs.size(), refreshMinutes());
            return;
        }

        long startedAt = System.currentTimeMillis();
        // base -> (quote -> rate) for pairs that refreshed, and base -> quotes that did not;
        // reported together in one line per base once the whole pass is done
        Map<String, Map<String, BigDecimal>> refreshedByBase = new LinkedHashMap<>();
        Map<String, List<String>> failedByBase = new LinkedHashMap<>();

        for (ExchangeRate pair : existingPairs) {
            String base = pair.getBaseCurrency();
            String quote = pair.getQuoteCurrency();
            boolean success = false;

            for (int attempt = 1; attempt <= config.getMaxRetries(); attempt++) {
                try {
                    BigDecimal rate = fxRateService.getRate(base, quote);
                    saveOrUpdateRates(List.of(new ExchangeRateEntry(base, quote, rate)));
                    refreshedByBase.computeIfAbsent(base, b -> new LinkedHashMap<>()).put(quote, rate);
                    success = true;
                    break;
                } catch (Exception e) {
                    log.warn("Could not update the {} to {} rate (try {} of {}). Reason: {}",
                            base, quote, attempt, config.getMaxRetries(), e.getMessage());
                }
            }

            if (!success) {
                failedByBase.computeIfAbsent(base, b -> new ArrayList<>()).add(quote);
            }
        }

        long tookMs = System.currentTimeMillis() - startedAt;
        refreshedByBase.forEach((base, rates) ->
                log.info("Currency rates updated: {} rates for {} refreshed in {}. Each value is how much of that currency 1 {} buys: {}",
                        rates.size(), currencyLabel(base), formatDuration(tookMs), base, formatRates(rates)));
        failedByBase.forEach((base, quotes) ->
                log.error("Could not update {} {} rate(s) ({}) after {} tries each. The last saved rates are still being used. Next attempt in {} minutes",
                        quotes.size(), currencyLabel(base), String.join(", ", quotes), config.getMaxRetries(), refreshMinutes()));
    }

    /** "US Dollar (USD)" for a known code, or just the code when Java does not know it. */
    private static String currencyLabel(String code) {
        try {
            return java.util.Currency.getInstance(code).getDisplayName(java.util.Locale.ENGLISH) + " (" + code + ")";
        } catch (IllegalArgumentException unknownCode) {
            return code;
        }
    }

    /** 62180 ms becomes "1 min 2 s"; under a minute stays in seconds. */
    private static String formatDuration(long ms) {
        long totalSeconds = Math.round(ms / 1000.0);
        return totalSeconds < 60 ? totalSeconds + " s" : (totalSeconds / 60) + " min " + (totalSeconds % 60) + " s";
    }

    private long refreshMinutes() {
        return Math.max(1, config.getRefreshIntervalMs() / 60_000);
    }

    /** Compact "AED=3.6725 AFN=64.94 ..." view of one base's rates, for a single log line. */
    private static String formatRates(Map<String, BigDecimal> rates) {
        return rates.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(" "));
    }

    /**
     * One-time bulk load for an empty exchange_rates collection: for each base
     * in ExchangeRateConfig.seedBases (USD), fetches every available rate from Frankfurter
     * and saves it (same path as POST /admin/exchange-rates/fetch). A failing
     * base is logged and skipped so the others still load; an empty list
     * disables seeding.
     */
    private void seedInitialRates() {
        List<String> bases = config.getSeedBases();
        if (bases == null || bases.isEmpty()) {
            return;
        }
        for (String base : bases) {
            if (base == null || base.isBlank()) continue;
            for (int attempt = 1; attempt <= config.getMaxRetries(); attempt++) {
                try {
                    fetchAndSaveAllRates(base.trim());
                    break;
                } catch (Exception e) {
                    log.warn("Could not download the starting rates for {} (try {} of {}). Reason: {}",
                            base, attempt, config.getMaxRetries(), e.getMessage());
                }
            }
        }
    }

    private void cacheInRedisBestEffort(String key, BigDecimal rate) {
        try {
            redisTemplate.opsForValue().set(key, rate, CACHE_TTL);
        } catch (Exception redisFailure) {
            log.warn("The rate was saved in the database, but could not be stored in the rate cache (Redis) under '{}'. Reason: {}",
                    key, redisFailure.getMessage());
        }
    }

    private String cacheKey(String base, String quote) {
        return CACHE_KEY_PREFIX + base + "-" + quote;
    }

    private String validate(String currency) {
        if (currency == null) {
            throw new IllegalArgumentException("Currency code is required");
        }
        String upper = currency.trim().toUpperCase();
        if (!CURRENCY_CODE_PATTERN.matcher(upper).matches()) {
            throw new IllegalArgumentException(
                    "'" + currency + "' doesn't look like a valid currency code — expected 3 uppercase letters "
                            + "(ISO 4217 format, e.g. USD, KES, GBP)");
        }
        return upper;
    }
}