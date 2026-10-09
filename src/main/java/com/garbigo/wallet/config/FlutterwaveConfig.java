package com.premisave.wallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Flutterwave v4 API configuration. v4 replaced v3's static Secret Key /
 * Public Key model entirely with OAuth 2.0 client-credentials — there is no
 * "public key" in v4. Base URL is now environment-specific (sandbox vs
 * production are genuinely different hosts, not just different key
 * prefixes on one URL the way v3 worked) — see baseUrl() below.
 *
 * See https://developer.flutterwave.com/docs/environments
 *     https://developer.flutterwave.com/docs/authentication
 */
@Data
@Component
@ConfigurationProperties(prefix = "flutterwave")
public class FlutterwaveConfig {

    /** OAuth2 client_credentials identity — replaces v3's secretKey/publicKey. */
    private String clientId;
    private String clientSecret;

    private String encryptionKey;

    /** "sandbox" or "production" — now DOES determine the base URL (see baseUrl()), unlike v3. */
    private String environment;

    /**
     * Arbitrary shared-secret string configured in the Flutterwave Dashboard
     * (Settings → Webhooks → Secret Hash). Used as the HMAC-SHA256 key to
     * verify the "flutterwave-signature" header — see
     * FlutterwaveService.verifyWebhookSignature. NOT a plain string
     * comparison the way v3's "verif-hash" was.
     */
    private String webhookSecretHash;

    /**
     * Where Flutterwave redirects the customer back to after completing a
     * charge's authorization step (3DS, mobile money approval, etc.).
     */
    private String redirectUrl;

    /**
     * SANDBOX TESTING ONLY. When set, every write call to Flutterwave
     * (customers, payment-methods, charges, direct-transfers) carries this
     * value as the "X-Scenario-Key" header, forcing a deterministic mock
     * outcome instead of the default (often indefinitely-pending, e.g.
     * mobile money's push-notification flow) sandbox behavior. See
     * https://developer.flutterwave.com/docs/testing for valid values —
     * e.g. "scenario:successful" / "scenario:failed" for transfers,
     * "scenario:auth_redirect" for mobile money charges,
     * "scenario:auth_pin&issuer:approved" for cards.
     *
     * Leave unset (default) for production — this must never be set outside
     * local/sandbox testing, since sending X-Scenario-Key against the
     * production API is rejected or ignored depending on endpoint, and its
     * presence in code/config is a signal something is misconfigured if it
     * ever reaches production.
     */
    private String sandboxScenarioKey;

    private final Transfer transfer = new Transfer();

    private static final String SANDBOX_BASE_URL = "https://developersandbox-api.flutterwave.com";
    private static final String PRODUCTION_BASE_URL = "https://f4bexperience.flutterwave.com";
    private static final String OAUTH_TOKEN_URL =
            "https://idp.flutterwave.com/realms/flutterwave/protocol/openid-connect/token";

    public String baseUrl() {
        return "production".equalsIgnoreCase(environment) ? PRODUCTION_BASE_URL : SANDBOX_BASE_URL;
    }

    /** Same URL for both environments — only the client_id/client_secret you send differs. */
    public String oauthTokenUrl() {
        return OAUTH_TOKEN_URL;
    }

    @Data
    public static class Transfer {
        /** Confirm actual tier limits with Flutterwave for your account. */
        private BigDecimal minAmount = new BigDecimal("1");
        private BigDecimal maxAmount = new BigDecimal("5000");

        /**
         * The currency your Flutterwave balance actually holds — used as
         * payment_instruction.source_currency on every payout (bank or
         * mobile money). This is NOT automatically the same as the
         * destination currency — Flutterwave's own documented sample for a
         * KES mobile-money payout uses source_currency=NGN, i.e.
         * source_currency reflects whatever currency your account balance
         * is funded in, not the recipient's currency.
         *
         * TODO: confirm your actual settlement/balance currency in the
         * Flutterwave dashboard and set flutterwave.transfer.source-currency
         * accordingly before relying on this in production. Left at "KES"
         * as a placeholder matching the wallet's own currency, which may or
         * may not be correct for your account.
         */
        private String sourceCurrency = "KES";
    }
}