package com.premisave.wallet.config;

import com.stripe.StripeClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "stripe")
public class StripeConfig {

    // sk_test_/sk_live_ (Standard) or rk_test_/rk_live_ (Restricted).
    // No separate sandbox/prod base URL like Flutterwave/M-Pesa — Stripe
    // always hits api.stripe.com; test vs live is decided by this key alone.
    // If using a restricted key, make sure it has permissions granted in the
    // Dashboard — new restricted keys default to Access policy "None" and
    // will 403 on every call until scoped (Customers, PaymentIntents,
    // PaymentMethods, Refunds, Payouts, and — for Connect — Accounts,
    // AccountLinks, Transfers as needed).
    private String secretKey;

    // whsec_... from Dashboard -> Developers -> Webhooks -> your endpoint ->
    // Signing secret. Used to verify the Stripe-Signature header on the
    // PLATFORM webhook (/payments/stripe/webhook — deposits, saved cards).
    // Connect events (account.updated, payout.paid/failed) arrive on a
    // SEPARATE endpoint with their own secret — see connect.webhookSecret
    // below; Stripe requires a distinct webhook destination for events on
    // connected accounts, it can't be folded into this same secret.
    private String webhookSecret;

    private final Connect connect = new Connect();

    @Bean
    StripeClient stripeClient() {
        return new StripeClient(secretKey);
    }

    /**
     * Stripe Connect (Express) config — used only by the bank-withdrawal
     * linking flow (StripeService.createConnectedAccountAndOnboardingLink /
     * transferAndPayout). Scoped to international users (US/UK/EU bank
     * accounts); Kenya stays on M-Pesa/Flutterwave.
     */
    @Data
    public static class Connect {
        /**
         * Where Stripe redirects the user if their onboarding link expired,
         * was already used, or is otherwise invalid — your frontend should
         * re-request a fresh link (POST /wallet/stripe/connect/link) and
         * redirect again from there.
         */
        private String refreshUrl;

        /** Where Stripe redirects the user after they finish (or exit) onboarding. Doesn't mean onboarding fully succeeded — check payoutsEnabled via GET /wallet/stripe/connect/account or the refresh endpoint. */
        private String returnUrl;

        /** whsec_... signing secret for the SEPARATE Connect webhook endpoint (Dashboard -> Webhooks -> your Connect destination -> "Listen to events on Connected accounts"). */
        private String webhookSecret;
    }
}