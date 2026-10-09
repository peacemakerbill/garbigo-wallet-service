package com.premisave.wallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * NOWPayments crypto payment gateway config. Unlike Stripe (test/live
 * decided purely by which key you use, against one fixed base URL),
 * NOWPayments has genuinely separate sandbox and production hosts, with
 * completely separate accounts, API keys, and IPN secrets. Mixing a
 * sandbox key with the production base URL (or vice versa) fails silently
 * at the auth/signature layer, not obviously — baseUrl, apiKey, and
 * ipnSecret must always be changed together, all three from the same
 * environment.
 *
 * No sandbox test-key/tunnel tooling exists here the way Stripe CLI's
 * `stripe listen` does — callbackUrl below needs to be a real reachable
 * HTTPS URL (e.g. via zrok/ngrok in dev) even while testing in sandbox.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "nowpayments")
public class NowPaymentsConfig {

    /**
     * https://api-sandbox.nowpayments.io for testing,
     * https://api.nowpayments.io for production. No trailing slash.
     */
    private String baseUrl;

    /** Sent as the "x-api-key" header on every request. Dashboard -> Settings -> Payments -> API keys. */
    private String apiKey;

    /**
     * Verifies the "x-nowpayments-sig" header on incoming IPN webhooks —
     * see NowPaymentsService.verifyIpnSignature. Dashboard -> Settings ->
     * Payments -> Instant payment notifications. NOT the same value as
     * apiKey above, and NOT interchangeable between sandbox/production.
     */
    private String ipnSecret;

    /**
     * Your own public HTTPS URL for /payments/nowpayments/webhook — sent
     * as ipn_callback_url on every Create Payment request.
     */
    private String callbackUrl;

    /**
     * Your NOWPayments DASHBOARD LOGIN email/password — NOT the same thing
     * as apiKey above, and a meaningfully more sensitive secret. Required
     * only for payouts: NOWPayments' payout endpoints need a short-lived
     * JWT (see NowPaymentsService.getAuthToken), obtained via POST /v1/auth
     * with these credentials, IN ADDITION to the x-api-key header every
     * other call uses. Deposits never touch these two fields at all.
     */
    private String payoutEmail;
    private String payoutPassword;
}