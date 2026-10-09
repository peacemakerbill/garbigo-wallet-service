package com.premisave.wallet.service;

import com.premisave.wallet.dto.DepositRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.SavedCard;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DepositStatus;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DepositRepository;
import com.premisave.wallet.repository.SavedCardRepository;
import com.premisave.wallet.repository.WalletRepository;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.SetupIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Stripe deposit business logic — deposits (card charging), saved card CRUD
 * (list/remove/set-default), Connect account linking initiation, and the
 * stuck-deposit sweeper. Split out of the former all-providers
 * DepositService, mirroring StripeService's existing role at the
 * API-integration layer.
 *
 * By far the largest of the five provider-specific deposit services — Stripe
 * carries card management and Connect linking on top of ordinary deposit
 * flow, which the other four providers don't have an equivalent of.
 *
 * Migrated to the Deposit entity (Stage 3) — same pattern
 * NowPaymentsDepositService pioneered (Stage 2): a dedicated Deposit
 * record instead of a generic Transaction row, plus
 * DepositTransactionRecorder creating the matching Transaction row on
 * confirmation. Only the deposit lifecycle logic changed here — every
 * saved-card and Connect-linking method is untouched, since none of them
 * ever read or write Transaction/Deposit at all.
 *
 * Called from DepositService.initiateDeposit (dispatcher) for initiation,
 * and directly from WalletController/PaymentCallbackController for the
 * card-management endpoints, confirm endpoint, and webhook handlers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeDepositService {

    private final WalletRepository walletRepository;
    private final DepositRepository depositRepository;
    private final SavedCardRepository savedCardRepository;
    private final StripeService stripeService;
    private final DepositTransactionRecorder depositTransactionRecorder;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;

    // ─── Deposits ────────────────────────────────────────────────────────────

    public PaymentResponse initiateStripeDeposit(String userId, String userEmail, DepositRequest request,
                                                  Wallet wallet, String idempotencyKey) {
        String currency = request.getCurrency() != null ? request.getCurrency() : "usd";

        String customerId = wallet.getStripeCustomerId();
        if (customerId == null) {
            customerId = stripeService.createCustomer(userEmail, userId);
            wallet.setStripeCustomerId(customerId);
            walletRepository.save(wallet);
        }

        StripeService.StripePaymentIntentResult result = stripeService.createOrChargePaymentIntent(
                customerId, wallet.getStripeDefaultPaymentMethodId(), request.getAmount(), currency, idempotencyKey, userId);

        log.info("Stripe deposit attempt: userId={} piId={} status={} requiresAction={}",
                userId, result.paymentIntentId(), result.status(), result.requiresAction());

        savePendingDeposit(userId, wallet.getId(), request.getAmount(), idempotencyKey);

        if ("succeeded".equals(result.status())) {
            creditWalletFromStripeCallback(idempotencyKey, request.getAmount(), result.paymentIntentId(),
                    currency, result.customerId(), result.paymentMethodId());
            return new PaymentResponse(true, result.paymentIntentId(), "Deposit successful (charged saved card).");
        }

        return new PaymentResponse(true, result.clientSecret(),
                "Stripe PaymentIntent created. Use the client_secret to confirm payment.");
    }

    @Transactional
    public Map<String, String> createStripeSetupIntent(String userId, String email) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found. Please create a wallet first."));

        String customerId = wallet.getStripeCustomerId();
        if (customerId == null) {
            customerId = stripeService.createCustomer(email, userId);
            wallet.setStripeCustomerId(customerId);
            walletRepository.save(wallet);
        }

        SetupIntent si = stripeService.createSetupIntent(customerId);
        return Map.of("clientSecret", si.getClientSecret(), "setupIntentId", si.getId());
    }

    /**
     * Confirms a SetupIntent and saves the resulting card.
     *
     * IMPORTANT: si.getPaymentMethod() is NOT re-validated by a plain GET —
     * a SetupIntent keeps remembering its payment_method even after that
     * PaymentMethod has since been detached elsewhere (e.g. via
     * removeSavedCard). A duplicate or stale call to this method with an
     * old setupIntentId — a re-sent Postman request, a retried frontend
     * call, etc. — would otherwise silently resurrect a "ghost" SavedCard
     * pointing at a PaymentMethod Stripe itself no longer considers
     * attached to anyone, which then fails with "not attached to a
     * customer" the next time someone tries to remove it. To prevent that,
     * this fetches the PaymentMethod itself and checks pm.getCustomer() —
     * null means it's already been detached, in which case this call is a
     * no-op rather than re-saving stale state.
     */
    @Transactional
    public void confirmStripeSetupIntent(String setupIntentId, String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        SetupIntent si = stripeService.retrieveSetupIntent(setupIntentId);
        if (!si.getCustomer().equals(wallet.getStripeCustomerId())) {
            throw new IllegalArgumentException("This SetupIntent does not belong to the authenticated user");
        }

        if (si.getPaymentMethod() != null) {
            String paymentMethodId = (String) si.getPaymentMethod();

            PaymentMethod pm;
            try {
                pm = stripeService.retrievePaymentMethod(paymentMethodId);
            } catch (Exception e) {
                log.warn("Setup intent confirmed but failed to fetch payment method {}: {}", paymentMethodId, e.getMessage());
                return;
            }

            if (pm.getCustomer() == null) {
                log.warn("SetupIntent {} payment_method {} is no longer attached to a customer — skipping " +
                        "(likely a stale or duplicate confirm call for a card already removed)",
                        setupIntentId, paymentMethodId);
                return;
            }

            String brand = pm.getCard() != null ? pm.getCard().getBrand() : null;
            String last4 = pm.getCard() != null ? pm.getCard().getLast4() : null;

            upsertSavedCardAsDefault(wallet, paymentMethodId, brand, last4);
            walletRepository.save(wallet);
            log.info("Stripe setup intent confirmed and card saved: userId={} setupIntentId={}", userId, setupIntentId);
        }
    }

    // ─── Saved cards (multiple per wallet) ──────────────────────────────────

    /**
     * Creates or updates the SavedCard row for this PaymentMethod, and makes
     * it the active default — matching common app UX where "add a card"
     * means "use this one now." Demotes every other SavedCard for the
     * wallet, and refreshes Wallet's denormalized cache fields
     * (stripeDefaultPaymentMethodId/CardBrand/CardLast4) to match.
     *
     * Idempotent, and shared across the three places a PaymentMethod can
     * independently be "first seen": confirmStripeSetupIntent above, the
     * setup_intent.succeeded webhook (attachSavedCardByCustomerId below),
     * and a first-time deposit that saves a new card as a side effect
     * (creditWalletFromStripeCallback below) — all three converge on the
     * same SavedCard row rather than creating duplicates, since
     * stripePaymentMethodId is uniquely indexed.
     *
     * Caller is still responsible for walletRepository.save(wallet)
     * afterward — kept consistent with every other method in this class
     * doing its own save at the end, rather than this helper reaching
     * outside its own concern.
     */
    private void upsertSavedCardAsDefault(Wallet wallet, String paymentMethodId, String brand, String last4) {
        SavedCard card = savedCardRepository.findByStripePaymentMethodId(paymentMethodId)
                .orElseGet(SavedCard::new);
        card.setWalletId(wallet.getId());
        card.setUserId(wallet.getUserId());
        card.setStripePaymentMethodId(paymentMethodId);
        if (brand != null) card.setBrand(brand);
        if (last4 != null) card.setLast4(last4);
        card.setDefault(true);
        card = savedCardRepository.save(card);

        String newDefaultId = card.getId();
        for (SavedCard other : savedCardRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId())) {
            if (!other.getId().equals(newDefaultId) && other.isDefault()) {
                other.setDefault(false);
                savedCardRepository.save(other);
            }
        }

        wallet.setStripeDefaultPaymentMethodId(paymentMethodId);
        wallet.setStripeCardBrand(card.getBrand());
        wallet.setStripeCardLast4(card.getLast4());
    }

    /** GET /wallet/stripe/cards — every saved card for this wallet, newest first. */
    public List<SavedCard> listSavedCards(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));
        return savedCardRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());
    }

    /**
     * Removes one specific saved card — DELETE /wallet/stripe/cards/{paymentMethodId}.
     * Detaches it on Stripe's side first (StripeService.detachPaymentMethod),
     * then deletes the SavedCard row. If the removed card was the default,
     * promotes the next-most-recent remaining card automatically rather
     * than leaving the wallet with no default and silently breaking the
     * next off-session deposit attempt.
     *
     * SECURITY: paymentMethodId is looked up scoped to the caller's own
     * wallet, not fetched globally — a caller can't detach another user's
     * card by guessing or reusing a pm_xxx id.
     */
    @Transactional
    public void removeSavedCard(String userId, String paymentMethodId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        SavedCard card = savedCardRepository.findByStripePaymentMethodId(paymentMethodId)
                .filter(c -> c.getWalletId().equals(wallet.getId()))
                .orElseThrow(() -> new IllegalStateException("This card is not linked to your wallet."));

        stripeService.detachPaymentMethod(paymentMethodId);
        savedCardRepository.delete(card);

        if (card.isDefault()) {
            List<SavedCard> remaining = savedCardRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());
            if (!remaining.isEmpty()) {
                SavedCard newDefault = remaining.get(0);
                newDefault.setDefault(true);
                savedCardRepository.save(newDefault);
                wallet.setStripeDefaultPaymentMethodId(newDefault.getStripePaymentMethodId());
                wallet.setStripeCardBrand(newDefault.getBrand());
                wallet.setStripeCardLast4(newDefault.getLast4());
            } else {
                wallet.setStripeDefaultPaymentMethodId(null);
                wallet.setStripeCardBrand(null);
                wallet.setStripeCardLast4(null);
            }
            walletRepository.save(wallet);
        }

        log.info("Stripe saved card removed: userId={} paymentMethodId={}", userId, paymentMethodId);
    }

    /**
     * Sets a specific already-saved card as the one initiateStripeDeposit
     * will charge — PUT /wallet/stripe/cards/{paymentMethodId}/default.
     * Doesn't call Stripe at all — this is purely an app-level concept;
     * Stripe's own "default payment method" is for invoicing, not what
     * this off-session deposit flow reads from.
     *
     * SECURITY:
     *  - Ownership: paymentMethodId is looked up scoped to the caller's own
     *    wallet, never fetched globally — a caller can't set another
     *    user's card as their own default by guessing/reusing a pm_xxx id.
     *  - Already-default rejection: matches freezeWallet/unfreezeWallet's
     *    pattern — an explicit 409 beats a silent no-op that reports
     *    "Default card updated" when nothing actually changed.
     */
    @Transactional
    public void setDefaultSavedCard(String userId, String paymentMethodId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        SavedCard card = savedCardRepository.findByStripePaymentMethodId(paymentMethodId)
                .filter(c -> c.getWalletId().equals(wallet.getId()))
                .orElseThrow(() -> new IllegalStateException("This card is not linked to your wallet."));

        if (card.isDefault()) {
            throw new IllegalStateException("This card is already your default card.");
        }

        for (SavedCard other : savedCardRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId())) {
            if (other.isDefault() && !other.getId().equals(card.getId())) {
                other.setDefault(false);
                savedCardRepository.save(other);
            }
        }

        card.setDefault(true);
        savedCardRepository.save(card);

        wallet.setStripeDefaultPaymentMethodId(card.getStripePaymentMethodId());
        wallet.setStripeCardBrand(card.getBrand());
        wallet.setStripeCardLast4(card.getLast4());
        walletRepository.save(wallet);

        log.info("Stripe default card changed: userId={} paymentMethodId={}", userId, paymentMethodId);
    }

    // ─── Stripe Connect (linking a bank account for withdrawals) ───────────
    // Unchanged — doesn't touch Transaction/Deposit either.

    /**
     * Starts (or resumes) Stripe Connect onboarding for this wallet, so the
     * user can later withdraw to their own international bank account — see
     * DisbursementService's STRIPE branch. Reuses the wallet's existing
     * connected account id if one's already on file (e.g. the user exited
     * onboarding partway through and is picking it back up), rather than
     * creating a duplicate Stripe Account every time this is called.
     *
     * The returned onboardingUrl is single-use — the caller must redirect
     * the user to it immediately, not cache/re-show it.
     */
    @Transactional
    public Map<String, String> createStripeConnectLink(String userId, String email) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found. Please create a wallet first."));

        StripeService.ConnectAccountLinkResult result = stripeService.createConnectedAccountAndOnboardingLink(
                wallet.getStripeConnectedAccountId(), email, userId);

        if (!result.accountId().equals(wallet.getStripeConnectedAccountId())) {
            wallet.setStripeConnectedAccountId(result.accountId());
            wallet.setStripePayoutsEnabled(false);
            walletRepository.save(wallet);
            log.info("Stripe Connect account linked to wallet: userId={} accountId={}", userId, result.accountId());
        }

        return Map.of("accountId", result.accountId(), "onboardingUrl", result.onboardingUrl());
    }

    // ─── Callbacks ───────────────────────────────────────────────────────────

    @Transactional
    public void creditWalletFromStripeCallback(String reference, BigDecimal amount, String paymentIntentId,
                                                String currency, String customerId, String paymentMethodId) {
        Deposit deposit = depositRepository.findByReference(reference)
                .orElseThrow(() -> new IllegalStateException("No pending deposit found for reference=" + reference));

        if (deposit.getStatus() == DepositStatus.SUCCESS) {
            log.info("Stripe deposit already processed for reference={} — skipping duplicate credit", reference);
            return;
        }

        Wallet wallet = walletRepository.findById(deposit.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + deposit.getWalletId()));

        wallet.setBalance(wallet.getBalance().add(amount));

        if (customerId != null && !customerId.equals(wallet.getStripeCustomerId())) {
            wallet.setStripeCustomerId(customerId);
        }
        if (paymentMethodId != null) {
            String brand = null;
            String last4 = null;
            try {
                PaymentMethod pm = stripeService.retrievePaymentMethod(paymentMethodId);
                if (pm.getCard() != null) {
                    brand = pm.getCard().getBrand();
                    last4 = pm.getCard().getLast4();
                }
            } catch (Exception e) {
                log.warn("Deposit succeeded but failed to fetch card display details: {}", e.getMessage());
            }
            upsertSavedCardAsDefault(wallet, paymentMethodId, brand, last4);
        }

        walletRepository.save(wallet);

        deposit.setStatus(DepositStatus.SUCCESS);
        deposit.setAmount(amount);
        deposit.setCurrency(resolveCurrency(currency));
        deposit.setProviderReference(paymentIntentId);
        String recipientName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
        deposit.setRecipientName(recipientName);
        depositRepository.save(deposit);

        depositTransactionRecorder.record(deposit.getUserId(), deposit.getWalletId(), amount,
                deposit, deposit.getReference());

        emailService.sendDepositConfirmation(wallet.getAccountNumber(), amount.toPlainString(),
                deposit.getCurrency().name(), deposit.getReference(), wallet.getBalance().toPlainString(),
                new EmailService.DepositDetails("Stripe", null, null,
                        null, paymentIntentId, recipientName, wallet.getAccountNumber(), wallet.getId()));

        log.info("Wallet credited via Stripe: reference={} amount={} piId={}", reference, amount, paymentIntentId);
    }

    @Transactional
    public PaymentResponse confirmStripeDeposit(String paymentIntentId, String callerUserId) {
        PaymentIntent pi = stripeService.retrievePaymentIntent(paymentIntentId);

        String reference = pi.getMetadata() != null ? pi.getMetadata().get("idempotency_key") : null;
        if (reference == null) {
            return new PaymentResponse(false, paymentIntentId,
                    "This PaymentIntent has no idempotency_key metadata and cannot be reconciled.");
        }

        Deposit deposit = depositRepository.findByReference(reference)
                .orElseThrow(() -> new IllegalStateException("No pending deposit found for Stripe reference=" + reference));

        if (!deposit.getUserId().equals(callerUserId)) {
            throw new IllegalArgumentException("This Stripe payment does not belong to the authenticated user");
        }

        if (deposit.getStatus() == DepositStatus.SUCCESS) {
            return new PaymentResponse(true, deposit.getId(), "Deposit already completed");
        }

        if (!"succeeded".equals(pi.getStatus())) {
            return new PaymentResponse(false, deposit.getId(),
                    "Payment has not completed yet (status: " + pi.getStatus() + ")");
        }

        BigDecimal amount = BigDecimal.valueOf(pi.getAmount()).divide(BigDecimal.valueOf(100));
        creditWalletFromStripeCallback(reference, amount, paymentIntentId, pi.getCurrency(),
                pi.getCustomer(), pi.getPaymentMethod());

        return new PaymentResponse(true, deposit.getId(), "Stripe deposit successful");
    }

    /**
     * Marks a Stripe deposit as failed — the counterpart to
     * markStkTransactionFailed / markPaypalTransactionFailed /
     * markFlutterwaveTransactionFailed (each in their own provider
     * service), which Stripe was missing until now. Triggered by the
     * payment_intent.payment_failed webhook (see
     * PaymentCallbackController.stripeWebhook) — a declined card, an
     * abandoned 3DS challenge that Stripe itself gives up on, etc. No
     * refund logic needed: the wallet is never credited for a PENDING
     * Stripe deposit (see initiateStripeDeposit), same reasoning as every
     * other provider's failure path.
     */
    @Transactional
    public void markStripeTransactionFailed(String reference, String reason) {
        depositRepository.findByReference(reference).ifPresentOrElse(deposit -> {
            if (deposit.getStatus() == DepositStatus.SUCCESS) {
                log.warn("Ignoring failure callback for already-completed Stripe reference={}", reference);
                return;
            }
            deposit.setStatus(DepositStatus.FAILED);
            deposit.setFailureReason(reason);
            depositRepository.save(deposit);
            log.warn("Stripe deposit failed: reference={} reason={}", reference, reason);
        }, () -> log.warn("Failure callback for unknown Stripe reference={}: {}", reference, reason));
    }

    @Transactional
    public void attachSavedCardByCustomerId(String stripeCustomerId, String paymentMethodId) {
        walletRepository.findByStripeCustomerId(stripeCustomerId).ifPresentOrElse(wallet -> {
            String brand = null;
            String last4 = null;
            try {
                PaymentMethod pm = stripeService.retrievePaymentMethod(paymentMethodId);
                if (pm.getCard() != null) {
                    brand = pm.getCard().getBrand();
                    last4 = pm.getCard().getLast4();
                }
            } catch (Exception e) {
                log.warn("SetupIntent succeeded but failed to fetch card display details: {}", e.getMessage());
            }
            upsertSavedCardAsDefault(wallet, paymentMethodId, brand, last4);
            walletRepository.save(wallet);
            log.info("Stripe card saved via webhook: customerId={} paymentMethodId={}", stripeCustomerId, paymentMethodId);
        }, () -> log.warn("setup_intent.succeeded webhook: no wallet found for customerId={}", stripeCustomerId));
    }

    // ─── Stuck-deposit sweeper (Stripe only — abandoned card collection) ────

    /**
     * Auto-fails Stripe deposits that have sat PENDING beyond the cutoff.
     * markStripeTransactionFailed above only fires on an EXPLICIT decline
     * (payment_intent.payment_failed) — a user who opens the card widget
     * and simply closes the app never triggers that webhook at all, since
     * nothing was actually attempted on Stripe's side. Without this, that
     * deposit would sit PENDING forever with no terminal state.
     *
     * Safe to auto-fail here — unlike DisbursementService.
     * flagStuckDisbursements, which only logs and never auto-resolves
     * (because money may already have moved for a disbursement) — since
     * the wallet is never credited for a PENDING deposit in the first
     * place (see initiateStripeDeposit). If a payment genuinely succeeded
     * but the webhook was somehow missed, confirmStripeDeposit can still
     * reconcile it manually after the fact; auto-failing here doesn't
     * foreclose that.
     *
     * Now a real provider-filtered query (findByProviderAndStatusAndCreatedAtBefore)
     * instead of the previous string-prefix matching against
     * Transaction.description — Deposit has a genuine provider field.
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void autoFailStuckStripeDeposits() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        List<Deposit> stuck = depositRepository
                .findByProviderAndStatusAndCreatedAtBefore("STRIPE", DepositStatus.PENDING, cutoff);

        for (Deposit deposit : stuck) {
            deposit.setStatus(DepositStatus.FAILED);
            deposit.setFailureReason("Abandoned — no confirmation within 30 minutes");
            depositRepository.save(deposit);
        }

        if (!stuck.isEmpty()) {
            log.warn("{} Stripe deposit(s) auto-failed after sitting PENDING beyond 30 minutes: {}",
                    stuck.size(), stuck.stream().map(Deposit::getId).toList());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void savePendingDeposit(String userId, String walletId, BigDecimal amount, String reference) {
        Deposit deposit = new Deposit();
        deposit.setUserId(userId);
        deposit.setWalletId(walletId);
        deposit.setAmount(amount);
        deposit.setCurrency(Currency.USD);
        deposit.setProvider("STRIPE");
        deposit.setChannel("STRIPE_CARD");
        deposit.setStatus(DepositStatus.PENDING);
        deposit.setReference(reference);
        depositRepository.save(deposit);
    }

    private Currency resolveCurrency(String currency) {
        if (currency == null) return Currency.USD;
        return switch (currency.toUpperCase()) {
            case "USD" -> Currency.USD;
            case "EUR" -> Currency.EUR;
            default    -> Currency.USD;
        };
    }
}