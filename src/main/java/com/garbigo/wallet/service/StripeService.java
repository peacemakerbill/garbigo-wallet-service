package com.premisave.wallet.service;

import com.premisave.wallet.config.StripeConfig;
import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Payout;
import com.stripe.model.SetupIntent;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodDetachParams;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.TransferCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Uses the injected StripeClient (StripeConfig.stripeClient()) rather than
 * the legacy static Stripe.apiKey / Customer.create(...) pattern — the
 * static pattern mutates global state at startup, which the Connect code
 * below would otherwise need to keep working around (e.g. per-connected-
 * account requests need their own RequestOptions regardless). Migrating
 * the whole file keeps one consistent calling convention throughout.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeService {

    private final StripeClient stripeClient;
    private final StripeConfig stripeConfig;

    // ─── Customer ──────────────────────────────────────────────────────────

    /**
     * Creates a Stripe Customer for this user — a stable anchor that lets a
     * saved card be reused for future off-session deposits. Note: test-mode
     * and live-mode customers are entirely separate objects, so a
     * stripeCustomerId saved while testing in sandbox will not exist if you
     * flip to live keys (nor vice versa).
     */
    public String createCustomer(String email, String userId) {
        try {
            CustomerCreateParams params = CustomerCreateParams.builder()
                    .setEmail(email)
                    .putMetadata("user_id", userId)
                    .build();
            Customer customer = stripeClient.v1().customers().create(params);
            log.info("Stripe Customer created: id={} userId={}", customer.getId(), userId);
            return customer.getId();
        } catch (StripeException e) {
            throw new RuntimeException("Failed to create Stripe customer: " + e.getMessage(), e);
        }
    }

    // ─── SetupIntent (save a card without charging it) ──────────────────────

    /**
     * Starts a SetupIntent so the frontend can collect and attach a card to
     * the given Customer via Stripe.js/Elements — raw card data goes
     * straight from the browser to Stripe and never touches our servers.
     * See DepositService.createStripeSetupIntent / confirmStripeSetupIntent.
     */
    public SetupIntent createSetupIntent(String customerId) {
        try {
            SetupIntentCreateParams params = SetupIntentCreateParams.builder()
                    .setCustomer(customerId)
                    .addPaymentMethodType("card")
                    .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
                    .build();
            SetupIntent intent = stripeClient.v1().setupIntents().create(params);
            log.info("Stripe SetupIntent created: id={} customerId={}", intent.getId(), customerId);
            return intent;
        } catch (StripeException e) {
            throw new RuntimeException("Failed to create Stripe SetupIntent: " + e.getMessage(), e);
        }
    }

    public SetupIntent retrieveSetupIntent(String setupIntentId) {
        try {
            return stripeClient.v1().setupIntents().retrieve(setupIntentId);
        } catch (StripeException e) {
            throw new RuntimeException("Failed to retrieve Stripe SetupIntent " + setupIntentId + ": " + e.getMessage(), e);
        }
    }

    public PaymentMethod retrievePaymentMethod(String paymentMethodId) {
        try {
            return stripeClient.v1().paymentMethods().retrieve(paymentMethodId);
        } catch (StripeException e) {
            throw new RuntimeException("Failed to retrieve Stripe PaymentMethod " + paymentMethodId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Detaches a saved card from its Stripe Customer — the counterpart to
     * the attach that happens implicitly via SetupIntent/PaymentIntent
     * setup_future_usage in createOrChargePaymentIntent above. After this,
     * the PaymentMethod can no longer be charged or re-attached to any
     * Customer (Stripe's own restriction, not something enforced here).
     * See DepositService.removeSavedCard, which clears the wallet's cached
     * stripeDefaultPaymentMethodId/CardBrand/CardLast4 after this succeeds.
     */
    public void detachPaymentMethod(String paymentMethodId) {
        try {
            stripeClient.v1().paymentMethods().detach(paymentMethodId, PaymentMethodDetachParams.builder().build());
            log.info("Stripe PaymentMethod detached: id={}", paymentMethodId);
        } catch (com.stripe.exception.InvalidRequestException e) {
            if (e.getMessage() != null && e.getMessage().contains("not attached to a customer")) {
                // Already detached on Stripe's side — e.g. a stale
                // SavedCard row resurrected by a re-confirmed/duplicate
                // SetupIntent confirm call (see DepositService.
                // confirmStripeSetupIntent javadoc). The end state the
                // caller wants — this card gone — is already true, so
                // treat it as a no-op success rather than a failure.
                log.warn("Stripe PaymentMethod {} was already detached — treating as already-removed", paymentMethodId);
                return;
            }
            throw new RuntimeException("Failed to detach Stripe PaymentMethod " + paymentMethodId + ": " + e.getMessage(), e);
        } catch (StripeException e) {
            throw new RuntimeException("Failed to detach Stripe PaymentMethod " + paymentMethodId + ": " + e.getMessage(), e);
        }
    }

    // ─── PaymentIntent (deposits) ────────────────────────────────────────────

    /**
     * Result of a deposit attempt. When requiresAction is true, clientSecret
     * must be confirmed client-side with Stripe.js before the deposit
     * completes (either a brand-new card, or a saved card that needed fresh
     * 3DS authentication this time).
     */
    public record StripePaymentIntentResult(
            String paymentIntentId, String clientSecret, String status,
            String customerId, String paymentMethodId, boolean requiresAction) {}

    /**
     * Creates (and, if a saved payment method is supplied, immediately
     * attempts to confirm) a PaymentIntent for a wallet deposit.
     *
     * - existingPaymentMethodId == null: first-time deposit. Returns a
     *   client_secret for the frontend to collect a card via Stripe.js.
     *   setup_future_usage(OFF_SESSION) is set so, once this succeeds, the
     *   card is saved on the Customer for one-click reloads next time.
     * - existingPaymentMethodId != null: attempts an off-session charge on
     *   the saved card immediately (confirm=true) — no frontend interaction
     *   needed at all if it succeeds. If the card issuer demands fresh
     *   authentication (CardException with code "authentication_required"),
     *   falls back to a client-confirmed PaymentIntent pre-filled with the
     *   same saved card, rather than failing the deposit outright.
     *
     * Explicitly restricted to payment_method_type "card" on every branch —
     * without this, Stripe falls back to whatever payment methods are
     * enabled on the Dashboard (this account had Link enabled), and any
     * redirect-capable method in that set makes Stripe require a return_url
     * at confirm time. flutter_stripe's plain card confirm doesn't supply
     * one, so an unrestricted PaymentIntent fails at confirm with
     * "you must provide a return_url" — same restriction createSetupIntent
     * above already applies, just missing here until now.
     */
    public StripePaymentIntentResult createOrChargePaymentIntent(
            String customerId, String existingPaymentMethodId, BigDecimal amountKes,
            String currency, String idempotencyKey, String userId) {

        long amountCents = amountKes.multiply(BigDecimal.valueOf(100)).longValue();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(currency.toLowerCase())
                .setDescription("Premisave wallet deposit")
                .setCustomer(customerId)
                .addPaymentMethodType("card")
                .putMetadata("idempotency_key", idempotencyKey)
                .putMetadata("user_id", userId);

        try {
            if (existingPaymentMethodId != null) {
                builder.setPaymentMethod(existingPaymentMethodId)
                        .setOffSession(true)
                        .setConfirm(true);
                PaymentIntent intent = stripeClient.v1().paymentIntents().create(builder.build(), options);
                log.info("Stripe off-session charge succeeded: id={} status={}", intent.getId(), intent.getStatus());
                return new StripePaymentIntentResult(intent.getId(), intent.getClientSecret(), intent.getStatus(),
                        intent.getCustomer(), intent.getPaymentMethod(), false);
            } else {
                builder.setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION);
                PaymentIntent intent = stripeClient.v1().paymentIntents().create(builder.build(), options);
                log.info("Stripe PaymentIntent created (new card): id={} status={}", intent.getId(), intent.getStatus());
                return new StripePaymentIntentResult(intent.getId(), intent.getClientSecret(), intent.getStatus(),
                        intent.getCustomer(), intent.getPaymentMethod(), false);
            }
        } catch (CardException e) {
            if (existingPaymentMethodId != null && "authentication_required".equals(e.getCode())) {
                log.warn("Saved card requires fresh authentication for userId={} — falling back to client-confirmed flow", userId);
                try {
                    PaymentIntentCreateParams retryParams = PaymentIntentCreateParams.builder()
                            .setAmount(amountCents)
                            .setCurrency(currency.toLowerCase())
                            .setDescription("Premisave wallet deposit")
                            .setCustomer(customerId)
                            .setPaymentMethod(existingPaymentMethodId)
                            .addPaymentMethodType("card")
                            .putMetadata("idempotency_key", idempotencyKey)
                            .putMetadata("user_id", userId)
                            .build();
                    RequestOptions retryOptions = RequestOptions.builder()
                            .setIdempotencyKey(idempotencyKey + "-3ds").build();
                    PaymentIntent retryIntent = stripeClient.v1().paymentIntents().create(retryParams, retryOptions);
                    return new StripePaymentIntentResult(retryIntent.getId(), retryIntent.getClientSecret(),
                            retryIntent.getStatus(), retryIntent.getCustomer(), retryIntent.getPaymentMethod(), true);
                } catch (StripeException retryEx) {
                    throw new RuntimeException("Stripe deposit failed on re-authentication retry: " + retryEx.getMessage(), retryEx);
                }
            }
            throw new RuntimeException("Stripe deposit failed: " + e.getMessage(), e);
        } catch (StripeException e) {
            throw new RuntimeException("Stripe deposit failed: " + e.getMessage(), e);
        }
    }

    public PaymentIntent retrievePaymentIntent(String paymentIntentId) {
        try {
            return stripeClient.v1().paymentIntents().retrieve(paymentIntentId);
        } catch (StripeException e) {
            throw new RuntimeException("Failed to retrieve Stripe PaymentIntent " + paymentIntentId + ": " + e.getMessage(), e);
        }
    }

    // ─── Payout (Premisave's own bank account — platform-level, NOT per-user) ─
    // Kept for operational use (e.g. sweeping Premisave's own Stripe balance
    // to Premisave's own bank). NOT used for user withdrawals — see the
    // Stripe Connect section below for that; a plain Payout has no way to
    // target an arbitrary user's own bank account.

    public String processPayout(BigDecimal amountKes, String currency) {
        long amountCents = amountKes.multiply(BigDecimal.valueOf(100)).longValue();
        try {
            PayoutCreateParams params = PayoutCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(currency.toLowerCase())
                    .setDescription("Premisave platform payout")
                    .build();
            Payout payout = stripeClient.v1().payouts().create(params);
            log.info("Stripe platform Payout created: id={} status={}", payout.getId(), payout.getStatus());
            return payout.getId();
        } catch (StripeException e) {
            log.error("Stripe platform Payout failed", e);
            throw new RuntimeException("Stripe payout failed: " + e.getMessage(), e);
        }
    }

    // ─── Stripe Connect — linking a bank account for withdrawals ─────────────
    //
    // Scoped to international users (US/UK/EU bank accounts) — Kenya stays
    // on M-Pesa/Flutterwave, since Stripe doesn't support Kenya as either a
    // platform or (self-serve) recipient country.
    //
    // Uses Express connected accounts on the stable v1 Accounts API with
    // controller properties (fees/losses collected by the platform, Express
    // dashboard) — NOT the new Accounts v2 API, which as of writing requires
    // an explicit sandbox-only preview enablement and isn't yet suitable for
    // production. Re-evaluate once v2 is generally available.
    //
    // One-time manual setup required in the Stripe Dashboard before this
    // works: Settings -> Connect -> Onboarding options -> configure which
    // countries Express accounts can onboard from (defaults to a very
    // narrow list otherwise).

    public record ConnectAccountLinkResult(String accountId, String onboardingUrl) {}

    /**
     * Creates an Express connected account (if the wallet doesn't already
     * have one — pass the existing id to reuse it, e.g. to resume/redo
     * onboarding) and a single-use Account Link the frontend redirects the
     * user to. Requesting the "transfers" capability is what lets Premisave
     * later Transfer funds into this account's balance; Stripe's hosted
     * onboarding form collects everything required for it (KYC + external
     * bank account) directly — raw bank details never touch our servers.
     */
    public ConnectAccountLinkResult createConnectedAccountAndOnboardingLink(
            String existingAccountId, String email, String userId) {
        try {
            String accountId = existingAccountId;

            if (accountId == null) {
                AccountCreateParams params = AccountCreateParams.builder()
                        .setEmail(email)
                        .setBusinessType(AccountCreateParams.BusinessType.INDIVIDUAL)
                        .setCapabilities(AccountCreateParams.Capabilities.builder()
                                .setTransfers(AccountCreateParams.Capabilities.Transfers.builder()
                                        .setRequested(true)
                                        .build())
                                .build())
                        .setController(AccountCreateParams.Controller.builder()
                                .setFees(AccountCreateParams.Controller.Fees.builder()
                                        .setPayer(AccountCreateParams.Controller.Fees.Payer.APPLICATION)
                                        .build())
                                .setLosses(AccountCreateParams.Controller.Losses.builder()
                                        .setPayments(AccountCreateParams.Controller.Losses.Payments.APPLICATION)
                                        .build())
                                .setStripeDashboard(AccountCreateParams.Controller.StripeDashboard.builder()
                                        .setType(AccountCreateParams.Controller.StripeDashboard.Type.EXPRESS)
                                        .build())
                                .build())
                        .putMetadata("user_id", userId)
                        .build();

                Account account = stripeClient.v1().accounts().create(params);
                accountId = account.getId();
                log.info("Stripe Connect account created: id={} userId={}", accountId, userId);
            }

            AccountLinkCreateParams linkParams = AccountLinkCreateParams.builder()
                    .setAccount(accountId)
                    .setRefreshUrl(stripeConfig.getConnect().getRefreshUrl())
                    .setReturnUrl(stripeConfig.getConnect().getReturnUrl())
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .build();

            AccountLink link = stripeClient.v1().accountLinks().create(linkParams);
            log.info("Stripe Connect onboarding link created: accountId={} userId={}", accountId, userId);

            return new ConnectAccountLinkResult(accountId, link.getUrl());
        } catch (StripeException e) {
            throw new RuntimeException("Failed to start Stripe Connect onboarding: " + e.getMessage(), e);
        }
    }

    /** Used by WalletService.refreshStripeConnectStatus for an on-demand status check, independent of the account.updated webhook. */
    public Account retrieveConnectedAccount(String accountId) {
        try {
            return stripeClient.v1().accounts().retrieve(accountId);
        } catch (StripeException e) {
            throw new RuntimeException("Failed to retrieve Stripe Connect account " + accountId + ": " + e.getMessage(), e);
        }
    }

    // ─── Stripe Connect — withdrawals (Transfer + Payout) ────────────────────

    public record ConnectPayoutResult(boolean success, String message, String transferId, String payoutId) {}

    /**
     * Two-step withdrawal: Transfer funds from Premisave's own Stripe
     * balance into the user's connected account, then immediately trigger a
     * Payout FROM that connected account to their external bank, acting on
     * their behalf via the Stripe-Account header (RequestOptions.
     * setStripeAccount) — the connected account never has to log in or
     * trigger anything themselves.
     *
     * These are two separate API calls with two separate failure points:
     *  - Transfer fails: no money has moved at all. Safe to just report
     *    failure — same as every other PENDING-never-happened provider path.
     *  - Payout fails AFTER a successful Transfer: money HAS already left
     *    Premisave's platform balance and is sitting in the connected
     *    account's own Stripe balance. This is NOT the same "nothing moved,
     *    nothing to refund" situation as the other providers' failure
     *    paths — see DisbursementService.completeStripeConnectDisbursement
     *    for how this is surfaced for manual reconciliation.
     *
     * amount/currency here are whatever's actually being sent to Stripe
     * (already FX-converted from the wallet's KES amount by the caller) —
     * this method doesn't do currency conversion itself.
     */
    public ConnectPayoutResult transferAndPayout(String connectedAccountId, BigDecimal amount,
                                                  String currency, String idempotencyKey) {
        long amountCents = amount.multiply(BigDecimal.valueOf(100)).longValue();

        Transfer transfer;
        try {
            TransferCreateParams transferParams = TransferCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(currency.toLowerCase())
                    .setDestination(connectedAccountId)
                    .setDescription("Premisave wallet withdrawal")
                    .build();
            RequestOptions transferOptions = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey + "-transfer")
                    .build();

            transfer = stripeClient.v1().transfers().create(transferParams, transferOptions);
            log.info("Stripe Connect transfer created: id={} accountId={} amount={} {}",
                    transfer.getId(), connectedAccountId, amount, currency);
        } catch (StripeException e) {
            log.warn("Stripe Connect transfer failed (no funds moved): accountId={}", connectedAccountId, e);
            return new ConnectPayoutResult(false, "Transfer to connected account failed: " + e.getMessage(), null, null);
        }

        try {
            PayoutCreateParams payoutParams = PayoutCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(currency.toLowerCase())
                    .setDescription("Premisave wallet withdrawal")
                    .build();
            RequestOptions payoutOptions = RequestOptions.builder()
                    .setStripeAccount(connectedAccountId)
                    .setIdempotencyKey(idempotencyKey + "-payout")
                    .build();

            Payout payout = stripeClient.v1().payouts().create(payoutParams, payoutOptions);
            log.info("Stripe Connect payout created: id={} accountId={} status={} transferId={}",
                    payout.getId(), connectedAccountId, payout.getStatus(), transfer.getId());

            return new ConnectPayoutResult(true, "Payout initiated", transfer.getId(), payout.getId());
        } catch (StripeException e) {
            log.error("Stripe Connect payout FAILED after a successful transfer — funds are sitting in the " +
                    "connected account's own Stripe balance (transferId={}, accountId={}); needs manual " +
                    "reconciliation (retry a payout on that account, or treat as an operational loss)",
                    transfer.getId(), connectedAccountId, e);
            return new ConnectPayoutResult(false,
                    "Transfer succeeded but payout to bank failed: " + e.getMessage(),
                    transfer.getId(), null);
        }
    }

    // ─── Platform balance ────────────────────────────────────────────────────

    public record CurrencyBalanceEntry(String currency, java.util.Map<String, BigDecimal> amounts) {}
    public record BalanceResult(boolean success, java.util.List<CurrencyBalanceEntry> balances, String message) {}

    /**
     * GET /v1/balance — Premisave's OWN Stripe balance (not any
     * customer's), broken down by currency with separate available and
     * pending figures. Simple, synchronous, real-time — the most
     * straightforward of the five providers' balance checks; no async
     * webhook round-trip the way M-Pesa's Account Balance API needs, and
     * no reporting-lag caveat the way PayPal's does.
     *
     * stripeClient.v1().balance() matches the same resource-per-method
     * convention every other call in this file already follows
     * (customers(), setupIntents(), paymentIntents(), payouts(),
     * accounts()) — Balance is a singleton resource in Stripe's API
     * (exactly one per account), so retrieve() takes no id.
     */
    public BalanceResult getBalance() {
        try {
            com.stripe.model.Balance balance = stripeClient.v1().balance().retrieve();
            java.util.Map<String, java.util.Map<String, BigDecimal>> byCurrency = new java.util.LinkedHashMap<>();

            for (var entry : balance.getAvailable()) {
                byCurrency.computeIfAbsent(entry.getCurrency().toUpperCase(), k -> new java.util.LinkedHashMap<>())
                        .put("available", BigDecimal.valueOf(entry.getAmount()).divide(BigDecimal.valueOf(100)));
            }
            for (var entry : balance.getPending()) {
                byCurrency.computeIfAbsent(entry.getCurrency().toUpperCase(), k -> new java.util.LinkedHashMap<>())
                        .put("pending", BigDecimal.valueOf(entry.getAmount()).divide(BigDecimal.valueOf(100)));
            }

            java.util.List<CurrencyBalanceEntry> result = byCurrency.entrySet().stream()
                    .map(e -> new CurrencyBalanceEntry(e.getKey(), e.getValue()))
                    .toList();

            log.info("Stripe balance retrieved: {} currencies", result.size());
            return new BalanceResult(true, result, "OK");
        } catch (StripeException e) {
            log.error("Stripe getBalance failed", e);
            return new BalanceResult(false, java.util.List.of(), "Stripe getBalance failed: " + e.getMessage());
        }
    }

    // ─── Webhook signature verification ──────────────────────────────────────
    // Used for BOTH the platform webhook (/payments/stripe/webhook) and the
    // Connect webhook (/payments/stripe/connect/webhook) — verification
    // itself doesn't depend on the API key/client, only on which secret is
    // passed in, so one method covers both call sites.

    /**
     * Lightweight check for Stripe's newer "Accounts v2" event format
     * (object: "v2.core.event") — a structurally different, incompatible
     * payload shape from the v1 Event this service's constructWebhookEvent
     * expects (created as an ISO-8601 string instead of a Unix timestamp,
     * related_object instead of data.object, etc.). Connect account
     * lifecycle changes (account created, capability status updated,
     * recipient configuration updated) can emit these automatically even
     * though this integration only creates accounts via the stable v1
     * API — see createConnectedAccountAndOnboardingLink's javadoc on why
     * v2 isn't used here. Attempting v1 deserialization on one of these
     * throws a confusing Gson NumberFormatException, not a clean error —
     * callers should check this FIRST and skip before ever reaching
     * constructWebhookEvent.
     */
    public boolean isV2Event(String payload) {
        return payload != null && payload.contains("\"object\":\"v2.core.event\"");
    }

    public com.stripe.model.Event constructWebhookEvent(String payload, String sigHeader, String webhookSecret)
            throws com.stripe.exception.SignatureVerificationException {
        try {
            return com.stripe.net.Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (com.stripe.exception.SignatureVerificationException e) {
            // Not wrapped — left as its real type so PaymentCallbackController
            // can catch SignatureVerificationException specifically and log
            // it as a clean one-liner (a mismatched/stale webhook secret is
            // an expected, recoverable config issue during testing, not a
            // bug — same reasoning as the isV2Event check above). A generic
            // RuntimeException here would force the caller to either log
            // every failure with a full stack trace or fragile message-string
            // matching to tell them apart.
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Stripe webhook payload: " + e.getMessage(), e);
        }
    }
}