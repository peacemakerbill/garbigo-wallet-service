package com.premisave.wallet.service;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.premisave.wallet.config.NowPaymentsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * NOWPayments API integration layer — raw HTTP calls, no official Java SDK
 * exists (their docs list Python/Node/PHP connectors, not Java), so this
 * follows the same hand-built OkHttp pattern already used for M-Pesa in
 * this codebase rather than a polished client library like stripe-java.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NowPaymentsService {

    private final NowPaymentsConfig config;

    private final OkHttpClient httpClient = new OkHttpClient();

    // USE_BIG_DECIMAL_FOR_FLOATS avoids float-rounding artifacts on amount
    // fields (price_amount, actually_paid, etc.) when parsing IPN bodies for
    // signature verification — see verifyIpnSignature's javadoc.
    private final JsonMapper objectMapper = JsonMapper.builder()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .build();

    // Cached payout JWT — obtained via POST /v1/auth, valid 5 minutes.
    // Refreshed with a 30-second safety margin rather than waiting for an
    // actual 401, so a payout call doesn't fail mid-request on an
    // about-to-expire token. synchronized rather than a more elaborate
    // concurrency structure since payouts are inherently low-frequency
    // (a human-initiated withdrawal, not a hot path) — contention here is
    // not a realistic concern.
    private String cachedPayoutToken;
    private Instant cachedPayoutTokenExpiry = Instant.EPOCH;

    public record CreatePaymentResult(boolean success, String paymentId, String payAddress, String payAmount,
                                       String payCurrency, String paymentStatus, String message) {
    }

    public record PaymentStatusResult(boolean success, String paymentStatus, String actuallyPaid, String message) {
    }

    public record CreatePayoutResult(boolean success, String payoutId, String status, String message) {
    }

    public record PayoutStatusResult(boolean success, String status, String message) {
    }

    public record EstimateResult(boolean success, BigDecimal estimatedAmount, String message) {
    }

    /**
     * Creates a crypto payment — POST /v1/payment. priceAmount/priceCurrency
     * is what you're charging in YOUR currency (KES here); payCurrency is
     * the crypto the customer will actually send (e.g. "usdttrc20", "btc").
     * NOWPayments quotes the crypto-equivalent amount and a one-time deposit
     * address in the response.
     *
     * sandboxCase is sandbox-only — one of "success", "failed",
     * "partially_paid", or "common" (confirmed valid values, from
     * NOWPayments' own API error response — "finished" is NOT one of
     * them, despite being a real payment_status value used elsewhere in
     * this integration; the two are easy to conflate but genuinely
     * different vocabularies). Makes NOWPayments immediately simulate that
     * outcome (via an IPN callback) without ever needing real crypto. Null
     * or blank in production, where it's simply not sent.
     */
    public CreatePaymentResult createPayment(BigDecimal priceAmount, String priceCurrency, String payCurrency,
                                              String orderId, String orderDescription, String sandboxCase) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("price_amount", priceAmount);
            body.put("price_currency", priceCurrency.toLowerCase());
            body.put("pay_currency", payCurrency.toLowerCase());
            body.put("order_id", orderId);
            if (orderDescription != null) {
                body.put("order_description", orderDescription);
            }
            body.put("ipn_callback_url", config.getCallbackUrl());
            if (sandboxCase != null && !sandboxCase.isBlank()) {
                body.put("case", sandboxCase);
            }

            String json = objectMapper.writeValueAsString(body);
            RequestBody requestBody = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/v1/payment")
                    .addHeader("x-api-key", config.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("NOWPayments createPayment failed: status={} body={}", response.code(), responseBody);
                    return new CreatePaymentResult(false, null, null, null, null, null,
                            "NOWPayments createPayment failed (" + response.code() + "): " + responseBody);
                }

                JsonNode node = objectMapper.readTree(responseBody);
                CreatePaymentResult result = new CreatePaymentResult(true,
                        node.path("payment_id").asString(null),
                        node.path("pay_address").asString(null),
                        node.path("pay_amount").asString(null),
                        node.path("pay_currency").asString(null),
                        node.path("payment_status").asString(null),
                        "Payment created");
                log.info("NOWPayments payment created: paymentId={} orderId={} payAddress={} payAmount={} {} status={}",
                        result.paymentId(), orderId, result.payAddress(), result.payAmount(), result.payCurrency(), result.paymentStatus());
                return result;
            }
        } catch (Exception e) {
            log.error("NOWPayments createPayment error: {}", e.getMessage(), e);
            return new CreatePaymentResult(false, null, null, null, null, null,
                    "NOWPayments createPayment error: " + e.getMessage());
        }
    }

    /** GET /v1/payment/{id} — manual status check, mainly for reconciliation; the IPN webhook is the primary path. */
    public PaymentStatusResult getPaymentStatus(String paymentId) {
        try {
            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/v1/payment/" + paymentId)
                    .addHeader("x-api-key", config.getApiKey())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("NOWPayments getPaymentStatus failed: status={} body={}", response.code(), responseBody);
                    return new PaymentStatusResult(false, null, null,
                            "NOWPayments getPaymentStatus failed (" + response.code() + "): " + responseBody);
                }

                JsonNode node = objectMapper.readTree(responseBody);
                return new PaymentStatusResult(true,
                        node.path("payment_status").asString(null),
                        node.path("actually_paid").asString(null),
                        "OK");
            }
        } catch (Exception e) {
            log.error("NOWPayments getPaymentStatus error: {}", e.getMessage(), e);
            return new PaymentStatusResult(false, null, null, "NOWPayments getPaymentStatus error: " + e.getMessage());
        }
    }

    /**
     * GET /v1/estimate — converts a fiat amount into its current crypto
     * equivalent using NOWPayments' own live rates. Needed for payouts
     * because FxRateService (Frankfurter, used for Stripe/PayPal FX) has
     * no cryptocurrency rates at all — this is NOWPayments' own quoting
     * engine, the same one the deposit side implicitly uses when it
     * quotes pay_amount on a Create Payment call, just called directly
     * here since a payout's amount has to be computed up front rather
     * than returned alongside address/QR-code creation.
     *
     * Only needs x-api-key, not the payout JWT — same auth tier as the
     * deposit-side calls.
     */
    public EstimateResult getEstimatedAmount(BigDecimal fiatAmount, String fiatCurrency, String cryptoCurrency) {
        try {
            String url = config.getBaseUrl() + "/v1/estimate?amount=" + fiatAmount
                    + "&currency_from=" + fiatCurrency.toLowerCase()
                    + "&currency_to=" + cryptoCurrency.toLowerCase();

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("x-api-key", config.getApiKey())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("NOWPayments getEstimatedAmount failed: status={} body={}", response.code(), responseBody);
                    return new EstimateResult(false, null,
                            "NOWPayments getEstimatedAmount failed (" + response.code() + "): " + responseBody);
                }
                JsonNode node = objectMapper.readTree(responseBody);
                BigDecimal estimatedAmount = node.has("estimated_amount")
                        ? new BigDecimal(node.get("estimated_amount").asString())
                        : null;
                if (estimatedAmount == null) {
                    return new EstimateResult(false, null, "NOWPayments estimate response missing 'estimated_amount': " + responseBody);
                }
                return new EstimateResult(true, estimatedAmount, "OK");
            }
        } catch (Exception e) {
            log.error("NOWPayments getEstimatedAmount error: {}", e.getMessage(), e);
            return new EstimateResult(false, null, "NOWPayments getEstimatedAmount error: " + e.getMessage());
        }
    }

    /**
     * Obtains (or reuses a cached) short-lived JWT for payout endpoints —
     * POST /v1/auth with the DASHBOARD LOGIN email/password (NOT the
     * apiKey), returning a token valid 5 minutes. Confirmed against
     * NOWPayments' own official Node.js SDK and multiple independent
     * integration guides, since this endpoint isn't in the same Postman
     * documentation as the deposit API and is easy to get wrong.
     *
     * Payout calls need BOTH this JWT (Authorization: Bearer) AND the
     * regular x-api-key header — deposits only ever need the latter.
     */
    private synchronized String getAuthToken() throws java.io.IOException {
        if (cachedPayoutToken != null && Instant.now().isBefore(cachedPayoutTokenExpiry)) {
            return cachedPayoutToken;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", config.getPayoutEmail());
        body.put("password", config.getPayoutPassword());

        String json = objectMapper.writeValueAsString(body);
        RequestBody requestBody = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(config.getBaseUrl() + "/v1/auth")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new java.io.IOException("NOWPayments auth failed (" + response.code() + "): " + responseBody);
            }
            JsonNode node = objectMapper.readTree(responseBody);
            String token = node.path("token").asString(null);
            if (token == null) {
                throw new java.io.IOException("NOWPayments auth response missing 'token' field: " + responseBody);
            }
            // 30s safety margin inside the real 5-minute window.
            cachedPayoutToken = token;
            cachedPayoutTokenExpiry = Instant.now().plusSeconds(270);
            return token;
        }
    }

    /**
     * Creates a payout — POST /v1/payout. Single-recipient even though
     * NOWPayments' own "Mass Payouts" branding implies batches; the
     * withdrawals array always carries exactly one entry here, matching
     * how Premisave models one user's one withdrawal request.
     *
     * DOES NOT move funds by itself. NOWPayments requires a SEPARATE
     * verification step (see verifyPayout below) before this executes —
     * per their own support docs, an unverified payout auto-rejects after
     * 1 hour. Whether that verification can be automated (TOTP app-based
     * 2FA) or needs a human (email-based 2FA) depends entirely on how 2FA
     * is configured on the NOWPayments account itself — see
     * DisbursementService.verifyNowPaymentsDisbursement's javadoc.
     */
    public CreatePayoutResult createPayout(String address, String currency, BigDecimal amount, String uniqueExternalId) {
        try {
            String token = getAuthToken();

            Map<String, Object> withdrawal = new LinkedHashMap<>();
            withdrawal.put("address", address);
            withdrawal.put("currency", currency.toLowerCase());
            withdrawal.put("amount", amount);
            withdrawal.put("unique_external_id", uniqueExternalId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ipn_callback_url", config.getCallbackUrl());
            body.put("withdrawals", List.of(withdrawal));

            String json = objectMapper.writeValueAsString(body);
            RequestBody requestBody = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/v1/payout")
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("x-api-key", config.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("NOWPayments createPayout failed: status={} body={}", response.code(), responseBody);
                    return new CreatePayoutResult(false, null, null,
                            "NOWPayments createPayout failed (" + response.code() + "): " + responseBody);
                }

                // Response wraps the single withdrawal under "withdrawals":
                // [{...}] — same shape as the request, per NOWPayments'
                // batch-oriented design. Not independently confirmed
                // against a live sandbox response at the time this was
                // written — verify the actual field path once you can hit
                // the real endpoint, and adjust if it differs.
                JsonNode node = objectMapper.readTree(responseBody);
                JsonNode first = node.path("withdrawals").isArray() && node.path("withdrawals").size() > 0
                        ? node.path("withdrawals").get(0)
                        : node;

                String payoutId = first.path("id").asString(null);
                String status = first.path("status").asString(null);

                log.info("NOWPayments payout created: payoutId={} uniqueExternalId={} address={} amount={} {} status={}",
                        payoutId, uniqueExternalId, address, amount, currency, status);
                return new CreatePayoutResult(true, payoutId, status, "Payout created — awaiting verification");
            }
        } catch (Exception e) {
            log.error("NOWPayments createPayout error: {}", e.getMessage(), e);
            return new CreatePayoutResult(false, null, null, "NOWPayments createPayout error: " + e.getMessage());
        }
    }

    /**
     * Verifies a created payout with a 2FA code — POST /v1/payout/{id}/verify
     * — the step that actually makes it execute. Per NOWPayments' own
     * support docs: if this isn't called within roughly 1 hour of
     * creation, the payout is automatically rejected.
     */
    public boolean verifyPayout(String payoutId, String verificationCode) {
        try {
            String token = getAuthToken();

            Map<String, Object> body = Map.of("verification_code", verificationCode);
            String json = objectMapper.writeValueAsString(body);
            RequestBody requestBody = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/v1/payout/" + payoutId + "/verify")
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("x-api-key", config.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("NOWPayments verifyPayout failed: payoutId={} status={} body={}",
                            payoutId, response.code(), responseBody);
                    return false;
                }
                log.info("NOWPayments payout verified: payoutId={}", payoutId);
                return true;
            }
        } catch (Exception e) {
            log.error("NOWPayments verifyPayout error: payoutId={} {}", payoutId, e.getMessage(), e);
            return false;
        }
    }

    /** GET /v1/payout/{id} — manual status check, mainly for reconciliation; the IPN webhook is the primary path. */
    public PayoutStatusResult getPayoutStatus(String payoutId) {
        try {
            String token = getAuthToken();

            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/v1/payout/" + payoutId)
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("x-api-key", config.getApiKey())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("NOWPayments getPayoutStatus failed: payoutId={} status={} body={}",
                            payoutId, response.code(), responseBody);
                    return new PayoutStatusResult(false, null,
                            "NOWPayments getPayoutStatus failed (" + response.code() + "): " + responseBody);
                }
                JsonNode node = objectMapper.readTree(responseBody);
                return new PayoutStatusResult(true, node.path("status").asString(null), "OK");
            }
        } catch (Exception e) {
            log.error("NOWPayments getPayoutStatus error: payoutId={} {}", payoutId, e.getMessage(), e);
            return new PayoutStatusResult(false, null, "NOWPayments getPayoutStatus error: " + e.getMessage());
        }
    }

    /**
     * Verifies the "x-nowpayments-sig" header on an incoming IPN webhook.
     *
     * NOWPayments signs HMAC-SHA512(ipnSecret, canonicalJson) where
     * canonicalJson is the recursively-key-sorted, compact JSON
     * serialization of the raw callback body — confirmed against
     * NOWPayments' own official Node.js SDK
     * (github.com/NowPaymentsIO/nowpayments-sdk-nodejs) and their official
     * WooCommerce plugin's PHP implementation
     * (github.com/NowPaymentsIO/nowpayments-payment-gateway-for-woocommerce).
     * Both simply re-serialize each language's own sorted-map JSON
     * representation and trust it round-trips consistently with what
     * NOWPayments computed server-side — this Java implementation does the
     * same (TreeMap for recursive key sorting at every nesting level,
     * Jackson for serialization, USE_BIG_DECIMAL_FOR_FLOATS above to avoid
     * float-rounding artifacts on amount fields).
     *
     * This is the same class of best-effort cross-language assumption
     * every official example makes, not an ironclad guarantee — if a
     * genuine IPN call ever fails verification, the first thing to check
     * is whether some numeric field's textual representation differs
     * between what NOWPayments sent and what Jackson reserializes it as
     * (e.g. "50.00" vs "50.0" vs "50"). This logs a warning rather than
     * silently dropping the event, so a mismatch is visible if it happens.
     */
    public boolean verifyIpnSignature(String rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("NOWPayments IPN missing x-nowpayments-sig header — rejecting");
            return false;
        }
        try {
            Object parsed = objectMapper.readValue(rawBody, Object.class);
            Object sorted = deepSortKeys(parsed);
            String canonicalJson = objectMapper.writeValueAsString(sorted);

            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(config.getIpnSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] hash = mac.doFinal(canonicalJson.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }

            boolean matches = hex.toString().equals(signatureHeader);
            if (!matches) {
                log.warn("NOWPayments IPN signature mismatch — check NOWPAYMENTS_IPN_SECRET matches the current "
                        + "sandbox/production dashboard value (these are NOT interchangeable between environments)");
            }
            return matches;
        } catch (Exception e) {
            log.error("Failed to verify NOWPayments IPN signature: {}", e.getMessage(), e);
            return false;
        }
    }

    // ─── Platform balance ────────────────────────────────────────────────────

    public record CurrencyBalanceEntry(String currency, java.util.Map<String, BigDecimal> amounts) {}
    public record BalanceResult(boolean success, java.util.List<CurrencyBalanceEntry> balances, String message) {}

    /**
     * GET /v1/balance — Premisave's OWN NOWPayments custody balance.
     *
     * Auth CONFIRMED via NOWPayments' own official onboarding
     * documentation, not assumed: only x-api-key is sent, no JWT/
     * Authorization header at all — the same simpler auth tier as the
     * deposit-side calls (createPayment, getPaymentStatus,
     * getEstimatedAmount), NOT the payout tier (createPayout,
     * verifyPayout, getPayoutStatus). An earlier version of this method
     * assumed the payout tier's auth (both the JWT via getAuthToken() AND
     * x-api-key) purely because this endpoint is grouped under NOWPayments'
     * own "Payout" category alongside those — that assumption was wrong,
     * confirmed by real testing: the JWT auth call itself failed with 403
     * ACCESS_DENIED (likely because the payout email/password credentials
     * were never configured, since the payout flow itself was never
     * tested), which blocked this balance check entirely even though
     * /v1/balance itself never needed that token in the first place.
     *
     * Response shape genuinely still unconfirmed, unlike the auth
     * mechanism above — parsed defensively below (currency code as the
     * key, whatever numeric fields are actually present as the values),
     * with the raw response logged so the actual shape is visible the
     * first time this actually runs against sandbox.
     */
    public BalanceResult getBalance() {
        try {
            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/v1/balance")
                    .addHeader("x-api-key", config.getApiKey())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                log.info("NOWPayments balance response (shape unconfirmed — see javadoc): {}", responseBody);

                if (!response.isSuccessful()) {
                    log.warn("NOWPayments getBalance failed: status={} body={}", response.code(), responseBody);
                    return new BalanceResult(false, java.util.List.of(),
                            "NOWPayments getBalance failed (" + response.code() + "): " + responseBody);
                }

                JsonNode node = objectMapper.readTree(responseBody);
                java.util.List<CurrencyBalanceEntry> result = new java.util.ArrayList<>();

                // Defensive parse — tries the most likely shapes without
                // assuming one specific structure, since this wasn't
                // confirmed against real docs the way other endpoints were.
                for (Map.Entry<String, JsonNode> field : node.properties()) {
                    JsonNode value = field.getValue();
                    if (value.isNumber() || (value.isString() && value.asString().matches("-?\\d+(\\.\\d+)?"))) {
                        Map<String, BigDecimal> amounts = new java.util.LinkedHashMap<>();
                        amounts.put("balance", new BigDecimal(value.asString()));
                        result.add(new CurrencyBalanceEntry(field.getKey().toUpperCase(), amounts));
                    }
                }

                return new BalanceResult(true, result,
                        result.isEmpty()
                                ? "Call succeeded but no balances parsed from the response — check the raw log line above for the actual shape"
                                : "OK");
            }
        } catch (Exception e) {
            log.error("NOWPayments getBalance error: {}", e.getMessage(), e);
            return new BalanceResult(false, java.util.List.of(), "NOWPayments getBalance error: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Object deepSortKeys(Object node) {
        if (node instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            ((Map<String, Object>) node).forEach((k, v) -> sorted.put(k, deepSortKeys(v)));
            return sorted;
        } else if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(deepSortKeys(item));
            }
            return result;
        }
        return node;
    }
}