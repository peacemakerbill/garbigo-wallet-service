package com.premisave.wallet.service;

import com.premisave.wallet.config.ExchangeRateConfig;
import com.premisave.wallet.dto.ExchangeRateEntry;
import com.premisave.wallet.entity.ExchangeRate;
import com.premisave.wallet.repository.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            log.warn("Redis read failed for exchange rate {}->{} — falling back to MongoDB directly. Cause: {}",
                    base, quote, redisFailure.getMessage());
        }

        var saved = exchangeRateRepository.findByBaseCurrencyAndQuoteCurrency(base, quote);
        if (saved.isPresent()) {
            cacheInRedisBestEffort(key, saved.get().getRate());
            return saved.get().getRate();
        }

        log.info("No saved rate for {}->{} yet — fetching live once and saving for future use", base, quote);
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
        log.info("Saved/updated {} exchange rate(s)", entries.size());
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
                log.warn("Skipping unexpected quote currency '{}' from Frankfurter bulk response for base={}",
                        entry.getKey(), validatedBase);
                continue;
            }
            entries.add(new ExchangeRateEntry(validatedBase, quote, entry.getValue()));
        }

        saveOrUpdateRates(entries);
        log.info("Fetched and saved {} rate(s) from Frankfurter for base={}", entries.size(), validatedBase);
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
            log.info("Exchange rate refresh skipped — no pairs have been saved/requested yet");
            return;
        }

        log.info("Starting scheduled exchange rate refresh for {} previously-used pair(s)", existingPairs.size());
        int refreshed = 0, failed = 0;

        for (ExchangeRate pair : existingPairs) {
            String base = pair.getBaseCurrency();
            String quote = pair.getQuoteCurrency();
            boolean success = false;

            for (int attempt = 1; attempt <= config.getMaxRetries(); attempt++) {
                try {
                    BigDecimal rate = fxRateService.getRate(base, quote);
                    saveOrUpdateRates(List.of(new ExchangeRateEntry(base, quote, rate)));
                    success = true;
                    break;
                } catch (Exception e) {
                    log.warn("Exchange rate refresh failed for {}->{} (attempt {}/{}): {}",
                            base, quote, attempt, config.getMaxRetries(), e.getMessage());
                }
            }

            if (success) {
                refreshed++;
            } else {
                failed++;
                log.error("Exchange rate refresh permanently failed for {}->{} after {} attempts — " +
                        "keeping the last saved rate (if any) in both MongoDB and Redis", base, quote, config.getMaxRetries());
            }
        }

        log.info("Exchange rate refresh complete: {} refreshed, {} failed", refreshed, failed);
    }

    private void cacheInRedisBestEffort(String key, BigDecimal rate) {
        try {
            redisTemplate.opsForValue().set(key, rate, CACHE_TTL);
        } catch (Exception redisFailure) {
            log.warn("Redis write failed for exchange rate cache key={} — MongoDB save still succeeded. Cause: {}",
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