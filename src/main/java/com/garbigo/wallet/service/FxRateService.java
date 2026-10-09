package com.premisave.wallet.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live foreign-exchange rates via Frankfurter (https://frankfurter.dev),
 * used to convert PayPal's USD-only amounts to/from the wallet's KES
 * balance (see DepositService and DisbursementService).
 *
 * IMPORTANT: this deliberately uses Frankfurter's v2 API
 * (https://api.frankfurter.dev/v2/...), NOT v1. The legacy v1 API only
 * serves the 31 currencies published directly by the ECB, which does NOT
 * include KES — a call to v1 for USD->KES will 404. v2 aggregates 84 central
 * banks and does cover KES. See https://frankfurter.dev/currencies/kes/
 *
 * Resilience notes:
 *  - Explicit short connect/read/write timeouts (5s) so a stalled or
 *    unreachable network fails fast rather than hanging on OkHttp's 10s
 *    default — important on networks where IPv6 is blocked/filtered and
 *    the JVM's default DNS resolution would otherwise try IPv6 addresses
 *    first (see ipv4PreferringDns below).
 *  - One automatic retry on transient connection failures
 *    (retryOnConnectionFailure), since a single dropped packet shouldn't
 *    fail an entire deposit/disbursement.
 *  - A short-lived (2 minute) last-known-good rate cache per currency pair
 *    is used ONLY if a fresh fetch fails after the retry — this is a
 *    genuine fallback for a transient FX outage, not a substitute for
 *    live rates in the normal case. Every fallback use is logged loudly
 *    (WARN) so degraded operation is visible. If Frankfurter has never
 *    successfully responded for a given pair, there is nothing to fall
 *    back to and this still throws, exactly as before.
 */
@Slf4j
@Service
public class FxRateService {

    private static final String RATE_URL = "https://api.frankfurter.dev/v2/rate";

    /** Bulk endpoint — returns EVERY quote currency Frankfurter supports against one base, in a single call. See getAllRates below. Confirmed directly from Frankfurter's own docs (frankfurter.dev): "curl https://api.frankfurter.dev/v2/rates?base=USD" — NOT /v2/latest, which was an earlier, incorrect assumption that returned a genuine 404 from Frankfurter itself. */
    private static final String RATES_URL = "https://api.frankfurter.dev/v2/rates";

    /** How long a previously-fetched rate remains usable as an emergency fallback. */
    private static final Duration FALLBACK_TTL = Duration.ofMinutes(2);

    /**
     * Prefers IPv4 addresses over IPv6. On networks where IPv6 is blocked
     * or filtered at the OS/router level (connections fail with something
     * like "Permission denied" or silently blackhole instead of failing
     * fast), letting OkHttp attempt IPv6 first can burn through the
     * connect timeout before ever trying a working IPv4 address. Sorting
     * IPv4 first here means the first attempt is the one most likely to
     * succeed on such networks, without disabling IPv6 entirely for
     * networks where it works fine.
     */
    private static final Dns IPV4_PREFERRING_DNS = hostname -> {
        List<InetAddress> addresses;
        try {
            addresses = Dns.SYSTEM.lookup(hostname);
        } catch (UnknownHostException e) {
            throw e;
        }
        return addresses.stream()
                .sorted(Comparator.comparingInt(addr -> addr.getAddress().length == 4 ? 0 : 1))
                .toList();
    };

    private final OkHttpClient http = new OkHttpClient.Builder()
            .dns(IPV4_PREFERRING_DNS)
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(5))
            .writeTimeout(Duration.ofSeconds(5))
            .retryOnConnectionFailure(true)
            .build();

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    private record CachedRate(BigDecimal rate, Instant fetchedAt) {}
    private final ConcurrentHashMap<String, CachedRate> lastKnownGood = new ConcurrentHashMap<>();

    /**
     * Returns how many units of {@code quote} equal 1 unit of {@code base}
     * right now, e.g. getRate("USD", "KES") returns the current USD->KES rate.
     *
     * Tries a live fetch first. If that fails (network error, non-2xx, or
     * unparseable response) AND a last-known-good rate for this exact pair
     * was fetched within the last {@link #FALLBACK_TTL}, that rate is
     * returned instead with a WARN log — otherwise the original failure
     * is thrown.
     *
     * @throws IllegalStateException if Frankfurter is unreachable/fails
     *         AND no usable fallback rate exists for this pair.
     */
    public BigDecimal getRate(String base, String quote) {
        String pairKey = base.toUpperCase() + "->" + quote.toUpperCase();

        try {
            BigDecimal rate = fetchLiveRate(base, quote);
            lastKnownGood.put(pairKey, new CachedRate(rate, Instant.now()));
            return rate;
        } catch (Exception liveFailure) {
            CachedRate cached = lastKnownGood.get(pairKey);
            if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(FALLBACK_TTL) <= 0) {
                log.warn("Frankfurter live fetch failed for {} — using last-known-good rate {} from {} ago. Cause: {}",
                        pairKey, cached.rate(), Duration.between(cached.fetchedAt(), Instant.now()), liveFailure.getMessage());
                return cached.rate();
            }
            throw liveFailure instanceof IllegalStateException ise ? ise
                    : new IllegalStateException("Failed to reach Frankfurter FX service for " + pairKey, liveFailure);
        }
    }

    /**
     * Fetches EVERY quote currency Frankfurter supports against the given
     * base, in ONE call — Frankfurter's /v2/rates?base=X endpoint, e.g.
     * base="USD" returns a rate for KES, EUR, GBP, and every other
     * currency Frankfurter covers, all at once, as a flat JSON array of
     * {"date": "...", "base": "USD", "quote": "KES", "rate": 129.37}
     * objects — confirmed directly from a real response; NOT a single
     * object with a "rates" map, which was this method's original,
     * untested assumption and returned a parse error against the real
     * API. Used specifically for bulk-populating the exchange rate
     * database directly from the live API in a single admin-triggered
     * action, rather than requiring one getRate() call — and one
     * manually-typed entry — per currency.
     *
     * Deliberately does NOT use the last-known-good fallback cache
     * getRate() has: this is a bulk, admin-triggered action outside any
     * transaction's hot path, so a failure here should surface clearly
     * rather than silently serve stale bulk data under a different name.
     *
     * @throws IllegalStateException if Frankfurter is unreachable, returns
     *         a non-2xx response, or the response isn't the expected
     *         array shape.
     */
    public Map<String, BigDecimal> getAllRates(String base) {
        HttpUrl parsedBase = HttpUrl.parse(RATES_URL);
        if (parsedBase == null) {
            throw new IllegalStateException("Failed to build Frankfurter URL for base=" + base);
        }
        HttpUrl url = parsedBase.newBuilder().addQueryParameter("base", base.toUpperCase()).build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new IllegalStateException(
                        "Frankfurter bulk rate lookup failed (" + response.code() + ") for base=" + base + ": " + body);
            }

            // Confirmed directly from a real response: /v2/rates?base=X
            // returns a FLAT ARRAY of {"date":"...", "base":"USD",
            // "quote":"KES", "rate":129.37} objects — one per supported
            // quote currency — NOT a single object with a "rates" map
            // the way the older v1 API (and this method's original,
            // untested assumption) worked. Each entry's own "date" is
            // ignored here — the point of this bulk fetch is the current
            // rate for each currency, and per-entry dates can genuinely
            // differ by a day or two depending on each market's last
            // trading/publishing day, which isn't something this method
            // needs to reconcile.
            JsonNode node = objectMapper.readTree(body);
            if (!node.isArray()) {
                throw new IllegalStateException(
                        "Frankfurter response was not the expected array shape for base=" + base + ": " + body);
            }

            Map<String, BigDecimal> rates = new LinkedHashMap<>();
            for (JsonNode entry : node) {
                JsonNode quoteNode = entry.path("quote");
                JsonNode rateNode = entry.path("rate");
                if (quoteNode.isMissingNode() || !quoteNode.isString()
                        || rateNode.isMissingNode() || !rateNode.isNumber()) {
                    log.warn("Skipping malformed entry in Frankfurter bulk response for base={}: {}", base, entry);
                    continue;
                }
                rates.put(quoteNode.asString(), rateNode.decimalValue());
            }

            log.info("Frankfurter bulk rates fetched for base={}: {} currencies", base, rates.size());
            return rates;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reach Frankfurter FX service for base=" + base, e);
        }
    }

    private BigDecimal fetchLiveRate(String base, String quote) {
        HttpUrl url = HttpUrl.parse(RATE_URL + "/" + base.toUpperCase() + "/" + quote.toUpperCase());
        if (url == null) {
            throw new IllegalStateException("Failed to build Frankfurter URL for " + base + "->" + quote);
        }

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new IllegalStateException(
                        "Frankfurter rate lookup failed (" + response.code() + ") for " + base + "->" + quote + ": " + body);
            }

            JsonNode node = objectMapper.readTree(body);
            JsonNode rateNode = node.path("rate");
            if (rateNode.isMissingNode() || !rateNode.isNumber()) {
                throw new IllegalStateException(
                        "Frankfurter response missing numeric 'rate' for " + base + "->" + quote + ": " + body);
            }

            BigDecimal rate = rateNode.decimalValue();
            log.info("Frankfurter live rate fetched: {} -> {} = {}", base, quote, rate);
            return rate;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to reach Frankfurter FX service for " + base + "->" + quote, e);
        }
    }
}