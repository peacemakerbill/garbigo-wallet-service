package com.premisave.wallet.service;

import com.premisave.wallet.dto.*;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.exception.DuplicateMpesaPhoneNumberException;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.WalletAlreadyExistsException;
import com.premisave.wallet.exception.WalletAlreadyFrozenException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.exception.WalletNotFrozenException;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import com.stripe.model.BankAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final MpesaService mpesaService;
    private final StripeService stripeService;
    private final UserNameResolver userNameResolver;

    /**
     * GET /internal/accounts — the wallet-owner directory another
     * Premisave service can page through, resolving name/phone/payment-
     * method details in one call rather than needing its own separate
     * lookups against auth-service for everything except the name.
     *
     * Performance note, worth knowing before this is called with a large
     * page size: fullName resolution is ONE UserNameResolver call
     * (itself one HTTP round-trip to auth-service) PER wallet in the
     * page — there's no batch/multi-email lookup capability in
     * AuthServiceClient to fold these into a single call. A page of 20
     * wallets means 20 separate auth-service calls made here,
     * sequentially. Pagination bounds the damage per request, but a
     * genuinely large page size would make this endpoint noticeably
     * slower than the other, name-free endpoints in this class.
     *
     * Uses UserNameResolver.resolveNameWithReachability rather than the
     * simpler resolveNameSafely every other caller in this codebase
     * uses — this is the one place that actually needs to tell "name
     * genuinely absent" apart from "auth-service is down", so the
     * controller can say so instead of silently returning a page full
     * of null fullName values with no explanation. If ANY wallet in the
     * page hits an unreachable result, the whole page is flagged —
     * during a real outage every call would fail the same way, so one
     * unreachable result is already a strong enough signal without
     * needing all of them to agree.
     */
    public record AccountDetailsPage(Page<AccountDetailsResponse> page, boolean authServiceReachable) {}

    public AccountDetailsPage getAllAccountDetails(Pageable pageable) {
        Page<Wallet> wallets = walletRepository.findAll(pageable);
        AtomicBoolean authServiceReachable = new AtomicBoolean(true);
        List<AccountDetailsResponse> content = wallets.getContent().stream()
                .map(wallet -> mapToAccountDetailsResponse(wallet, authServiceReachable))
                .toList();
        Page<AccountDetailsResponse> page = new PageImpl<>(content, pageable, wallets.getTotalElements());
        return new AccountDetailsPage(page, authServiceReachable.get());
    }

    private AccountDetailsResponse mapToAccountDetailsResponse(Wallet wallet, AtomicBoolean authServiceReachable) {
        AccountDetailsResponse r = new AccountDetailsResponse();
        r.setUserId(wallet.getUserId());
        r.setAccountNumber(wallet.getAccountNumber());
        UserNameResolver.NameResolution resolution = userNameResolver.resolveNameWithReachability(wallet.getAccountNumber());
        r.setFullName(resolution.fullName());
        if (!resolution.authServiceReachable()) {
            authServiceReachable.set(false);
        }
        r.setFrozen(wallet.isFrozen());
        r.setMpesaPhoneNumber(wallet.getMpesaPhoneNumber());
        r.setPochiPhoneNumber(wallet.getPochiPhoneNumber());
        r.setPaypalEmail(wallet.getPaypalEmail());
        r.setPaypalConnectedEmail(wallet.getPaypalConnectedEmail());
        r.setStripeConnected(wallet.getStripeConnectedAccountId() != null);
        r.setStripePayoutsEnabled(wallet.isStripePayoutsEnabled());
        r.setFlutterwavePaymentMethodNetwork(wallet.getFlutterwavePaymentMethodNetwork());
        r.setFlutterwavePaymentMethodPhone(wallet.getFlutterwavePaymentMethodPhone());
        r.setCreatedAt(wallet.getCreatedAt());
        return r;
    }

    /**
     * Get wallet by account number (email)
     */
    public WalletResponse getWallet(String accountNumber) {
        Wallet wallet = walletRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for: " + accountNumber));
        return mapToResponse(wallet);
    }

    /**
     * Get wallet balance
     */
    public WalletBalanceResponse getBalance(String accountNumber) {
        Wallet wallet = walletRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for: " + accountNumber));
        return new WalletBalanceResponse(wallet.getBalance(), wallet.getCurrency().name(), wallet.isFrozen());
    }

    /**
     * Create wallet if it doesn't exist.
     *
     * Rejects outright if a wallet already exists — checked by BOTH
     * accountNumber (email) and userId, since a mismatch between the two
     * would otherwise let a caller end up owning two wallet records (e.g.
     * userId already has a wallet under a different/older email). Callers
     * should GET /wallet instead of re-POSTing /wallet/create.
     */
    @Transactional
    public WalletResponse createWallet(String userId, String email) {
        Optional<Wallet> existingByEmail = walletRepository.findByAccountNumber(email);
        if (existingByEmail.isPresent()) {
            log.warn("Wallet creation rejected — wallet already exists for email={}", email);
            throw new WalletAlreadyExistsException(
                    "You already have a wallet for this account. Please refresh the page to view it.");
        }

        Optional<Wallet> existingByUserId = walletRepository.findByUserId(userId);
        if (existingByUserId.isPresent()) {
            log.warn("Wallet creation rejected — wallet already exists for userId={}", userId);
            throw new WalletAlreadyExistsException(
                    "You already have a wallet. Please refresh the page to view it.");
        }

        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setAccountNumber(email);
        wallet.setBalance(BigDecimal.ZERO);
        // Fixed: this explicit call was overriding Wallet.currency's own
        // field default (changed to Currency.USD earlier) — a field
        // default only applies if nothing else sets it afterward, and
        // this line was doing exactly that on every single wallet
        // creation, which is why new wallets kept showing KES despite
        // the entity-level change.
        wallet.setCurrency(Currency.USD);
        wallet.setFrozen(false);

        wallet = walletRepository.save(wallet);
        log.info("Successfully created wallet for userId={} | email={}", userId, email);

        return mapToResponse(wallet);
    }

    /**
     * Freeze wallet.
     *
     * Rejects if the wallet is already frozen — callers get an explicit,
     * actionable error instead of a silent no-op that looks successful.
     */
    @Transactional
    public WalletResponse freezeWallet(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            log.warn("Freeze rejected — wallet already frozen for userId: {}", userId);
            throw new WalletAlreadyFrozenException("This wallet is already frozen.");
        }

        wallet.setFrozen(true);
        wallet = walletRepository.save(wallet);
        log.info("Wallet frozen for userId: {}", userId);

        return mapToResponse(wallet);
    }

    /**
     * Unfreeze wallet.
     *
     * Rejects if the wallet is not currently frozen — same reasoning as
     * freezeWallet above: an explicit error beats a silent no-op.
     */
    @Transactional
    public WalletResponse unfreezeWallet(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (!wallet.isFrozen()) {
            log.warn("Unfreeze rejected — wallet is not frozen for userId: {}", userId);
            throw new WalletNotFrozenException("This wallet is not frozen — nothing to unfreeze.");
        }

        wallet.setFrozen(false);
        wallet = walletRepository.save(wallet);
        log.info("Wallet unfrozen for userId: {}", userId);

        return mapToResponse(wallet);
    }

    /**
     * Get detailed wallet statement with summary.
     * Read-only — intentionally allowed even while frozen, so a frozen
     * user can still see their transaction history and understand their
     * balance; only money-movement and payout-destination changes are
     * blocked while frozen (see validateWalletForTransaction,
     * updatePaypalEmail, updateMpesaPhoneNumber).
     */
    public WalletStatementResponse getStatement(String email, WalletStatementRequest request) {
        Wallet wallet = walletRepository.findByAccountNumber(email)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for email: " + email));

        LocalDate toDate = request.getToDate() != null ? request.getToDate() : LocalDate.now();

        // Fetch all transactions for the user
        List<Transaction> allTransactions = transactionRepository
                .findByUserIdOrderByCreatedAtDesc(wallet.getUserId());

        // Filter by date range
        List<TransactionResponse> filteredTransactions = allTransactions.stream()
                .filter(tx -> {
                    LocalDate txDate = tx.getCreatedAt().toLocalDate();
                    return !txDate.isBefore(request.getFromDate()) && !txDate.isAfter(toDate);
                })
                .filter(tx -> request.getType() == null || tx.getType().name().equals(request.getType()))
                .map(this::mapToTransactionResponse)
                .collect(Collectors.toList());

        // Calculate totals
        BigDecimal totalCredits = filteredTransactions.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(TransactionResponse::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalDebits = filteredTransactions.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .map(TransactionResponse::getAmount)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new WalletStatementResponse(
                wallet.getAccountNumber(),
                wallet.getBalance().subtract(totalCredits).add(totalDebits), // Approximate opening balance
                wallet.getBalance(),
                totalCredits,
                totalDebits,
                request.getFromDate(),
                toDate,
                filteredTransactions
        );
    }

    /**
     * Helper method to check if wallet is active and has sufficient funds.
     * Used by every money-movement flow (PaymentService, TransferService,
     * DisbursementService) — the single source of truth for "can this
     * wallet spend right now."
     */
    public void validateWalletForTransaction(String userId, BigDecimal amount) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            throw new WalletFrozenException("Wallet is frozen and cannot perform transactions until it is unfrozen");
        }

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient balance. Available: " + wallet.getBalance());
        }
    }

    private TransactionResponse mapToTransactionResponse(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getType(),
                tx.getStatus(),
                tx.getAmount(),
                tx.getCurrency() != null ? tx.getCurrency().name() : "KES",
                tx.getDescription(),
                tx.getCreatedAt()
        );
    }

    /**
     * Sets/updates the PayPal payout email.
     * Blocked while frozen — same reasoning as blocking transfers/
     * disbursements: a frozen wallet's money-movement configuration
     * shouldn't be changeable either (prevents a compromised/flagged
     * account from redirecting future payouts while under review).
     */
    @Transactional
    public WalletResponse updatePaypalEmail(String userId, String paypalEmail) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            throw new WalletFrozenException(
                    "Wallet is frozen — payout details cannot be changed until it is unfrozen");
        }

        wallet.setPaypalEmail(paypalEmail);
        wallet = walletRepository.save(wallet);
        log.info("PayPal email updated for userId={}", userId);
        return mapToResponse(wallet);
    }

    /**
     * Sets/updates the M-Pesa phone number used for quick deposits (STK
     * push — no need to type a number every time) and disbursements.
     * Resolved authoritatively from here by DepositService/
     * DisbursementService — never taken from a deposit/disbursement
     * request itself — same reasoning as the PayPal email pattern above
     * (eliminates typo/mistargeted-payout risk).
     *
     * Must be unique across wallets — this number now doubles as the C2B
     * Pay Bill account reference (see MpesaC2BService), so two wallets
     * sharing it would misdirect deposits. Rejected with
     * DuplicateMpesaPhoneNumberException if another wallet already owns it.
     *
     * Blocked while frozen — same reasoning as updatePaypalEmail above.
     */
    @Transactional
    public WalletResponse updateMpesaPhoneNumber(String userId, String phoneNumber) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            throw new WalletFrozenException(
                    "Wallet is frozen — payout details cannot be changed until it is unfrozen");
        }

        String normalizedPhone = mpesaService.normalizePhone(phoneNumber);
        final String currentWalletId = wallet.getId();

        walletRepository.findByMpesaPhoneNumber(normalizedPhone)
                .filter(other -> !other.getId().equals(currentWalletId))
                .ifPresent(other -> {
                    log.warn("M-Pesa phone update rejected — {} already registered to a different wallet (userId={})",
                            normalizedPhone, other.getUserId());
                    throw new DuplicateMpesaPhoneNumberException(
                            "This M-Pesa number is already registered to another account.");
                });

        wallet.setMpesaPhoneNumber(normalizedPhone);
        wallet = walletRepository.save(wallet);
        log.info("M-Pesa phone number updated for userId={} accountNumber={}", userId, wallet.getAccountNumber());
        return mapToResponse(wallet);
    }

    /**
     * Sets/updates the phone number the user's Pochi la Biashara business
     * account is registered under — distinct from mpesaPhoneNumber above,
     * since a Pochi account can be registered on a different line than a
     * user's regular personal M-Pesa number. Resolved authoritatively from
     * here by DisbursementService (see resolveVerifiedPochiPhoneNumber) —
     * never taken from a disbursement request itself — same reasoning as
     * mpesaPhoneNumber/paypalEmail above (eliminates typo/mistargeted-payout
     * risk). Blocked while frozen — same reasoning as updateMpesaPhoneNumber.
     */
    @Transactional
    public WalletResponse updatePochiPhoneNumber(String userId, String phoneNumber) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            throw new WalletFrozenException(
                    "Wallet is frozen — payout details cannot be changed until it is unfrozen");
        }

        wallet.setPochiPhoneNumber(mpesaService.normalizePhone(phoneNumber));
        wallet = walletRepository.save(wallet);
        log.info("Pochi phone number updated for userId={}", userId);
        return mapToResponse(wallet);
    }

    /**
     * Unlinks the wallet's saved PayPal account (vault_id/customer_id/
     * connected email) so future PayPal deposits no longer auto-reuse it —
     * the next deposit will create a fresh order and, if requestVaulting
     * succeeds, save whatever account the user pays with next.
     *
     * NOT blocked while frozen — same reasoning as
     * DepositService.createStripeSetupIntent: this doesn't move money, it
     * only changes which saved account future deposits would reuse, so
     * there's no reason to prevent it during a freeze.
     *
     * This does NOT revoke the vault token on PayPal's side — PayPal
     * doesn't expose a safe customer-scoped "delete this vault_id" call via
     * server API. It only stops Premisave from storing/reusing it. Distinct
     * from paypalEmail (the manual payout destination set via
     * updatePaypalEmail above) — that's left untouched here.
     */
    @Transactional
    public WalletResponse disconnectPaypalAccount(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.getPaypalVaultId() == null) {
            log.warn("PayPal disconnect rejected — no PayPal account linked for userId: {}", userId);
            throw new IllegalStateException("No PayPal account is linked to this wallet.");
        }

        wallet.setPaypalVaultId(null);
        wallet.setPaypalCustomerId(null);
        wallet.setPaypalConnectedEmail(null);
        wallet = walletRepository.save(wallet);
        log.info("PayPal account disconnected for userId={}", userId);

        return mapToResponse(wallet);
    }

    /**
     * Read-only lookup of the wallet's linked PayPal account, for the
     * frontend to render (e.g. a "Connected: user@example.com" / "Not
     * connected" state on a payment methods screen). Allowed while frozen —
     * same reasoning as getStatement: it's read-only.
     */
    public PaypalAccountResponse getPaypalAccount(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        return new PaypalAccountResponse(wallet.getPaypalVaultId() != null, wallet.getPaypalConnectedEmail());
    }

    // ─── Stripe Connect (bank withdrawal linking) ───────────────────────────

    /**
     * Unlinks the wallet's Stripe Connect account. Unlike
     * disconnectPaypalAccount above, this doesn't (and can't) revoke
     * anything on Stripe's side either — Express connected accounts don't
     * support an OAuth-style deauthorize the way Standard accounts do,
     * since Premisave created and operationally controls this account, not
     * the user. This only stops Premisave from sending future transfers/
     * payouts to it; the underlying Stripe Account object is left
     * untouched, and a future re-link creates a brand-new connected
     * account rather than reusing the forgotten one.
     *
     * NOT blocked while frozen — same reasoning as disconnectPaypalAccount:
     * this doesn't move money, only changes a payout destination reference.
     */
    @Transactional
    public WalletResponse disconnectStripeConnectAccount(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.getStripeConnectedAccountId() == null) {
            log.warn("Stripe Connect disconnect rejected — no account linked for userId: {}", userId);
            throw new IllegalStateException("No Stripe bank account is linked to this wallet.");
        }

        wallet.setStripeConnectedAccountId(null);
        wallet.setStripeConnectedAccountCountry(null);
        wallet.setStripePayoutsEnabled(false);
        wallet.setStripeExternalBankName(null);
        wallet.setStripeExternalBankLast4(null);
        wallet = walletRepository.save(wallet);
        log.info("Stripe Connect account disconnected for userId={}", userId);

        return mapToResponse(wallet);
    }

    /**
     * Syncs a wallet's cached Stripe Connect status from a Stripe Account
     * object — payouts_enabled and the linked bank's display name/last4
     * (for "Chase •••• 4242" style UI without an extra Stripe call per page
     * load).
     *
     * NOT CURRENTLY CALLED FROM ANYWHERE — account status is handled via
     * on-demand polling instead (refreshStripeConnectStatus below, called
     * from POST /wallet/stripe/connect/refresh), not a webhook push, since
     * account status has no urgency the way a payout result does. Kept here
     * in case you want push-based sync later: add an "account.updated"
     * branch back into PaymentCallbackController.stripeConnectWebhook and
     * call this with the deserialized Account, same shape as the
     * payout.paid/payout.failed branches already there.
     */
    @Transactional
    public void updateStripeConnectAccountStatus(com.stripe.model.Account account) {
        walletRepository.findByStripeConnectedAccountId(account.getId()).ifPresentOrElse(wallet -> {
            applyStripeAccountStatus(wallet, account);
            walletRepository.save(wallet);
            log.info("Stripe Connect account status synced via webhook: walletId={} accountId={} payoutsEnabled={}",
                    wallet.getId(), account.getId(), wallet.isStripePayoutsEnabled());
        }, () -> log.warn("account.updated webhook: no wallet found for Stripe Connect accountId={}", account.getId()));
    }

    /**
     * On-demand equivalent of updateStripeConnectAccountStatus above — used
     * right after the user returns from Stripe's hosted onboarding
     * (return_url), so the frontend can show accurate status immediately
     * rather than waiting on the account.updated webhook to arrive (which
     * can lag, or be missed entirely if the Connect webhook endpoint isn't
     * set up yet in an early testing environment).
     */
    @Transactional
    public Map<String, Object> refreshStripeConnectStatus(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.getStripeConnectedAccountId() == null) {
            throw new IllegalStateException("No Stripe bank account is linked to this wallet.");
        }

        com.stripe.model.Account account = stripeService.retrieveConnectedAccount(wallet.getStripeConnectedAccountId());
        applyStripeAccountStatus(wallet, account);
        walletRepository.save(wallet);

        log.info("Stripe Connect account status refreshed manually: walletId={} accountId={} payoutsEnabled={}",
                wallet.getId(), account.getId(), wallet.isStripePayoutsEnabled());

        return stripeConnectStatusMap(wallet);
    }

    private void applyStripeAccountStatus(Wallet wallet, com.stripe.model.Account account) {
        wallet.setStripePayoutsEnabled(Boolean.TRUE.equals(account.getPayoutsEnabled()));
        wallet.setStripeConnectedAccountCountry(account.getCountry());

        if (account.getExternalAccounts() != null && account.getExternalAccounts().getData() != null) {
            account.getExternalAccounts().getData().stream()
                    .filter(ea -> ea instanceof BankAccount)
                    .map(ea -> (BankAccount) ea)
                    .findFirst()
                    .ifPresent(bank -> {
                        wallet.setStripeExternalBankName(bank.getBankName());
                        wallet.setStripeExternalBankLast4(bank.getLast4());
                    });
        }
    }

    private Map<String, Object> stripeConnectStatusMap(Wallet wallet) {
        Map<String, Object> info = new HashMap<>();
        info.put("linked", wallet.getStripeConnectedAccountId() != null);
        info.put("accountId", wallet.getStripeConnectedAccountId());
        info.put("payoutsEnabled", wallet.isStripePayoutsEnabled());
        info.put("country", wallet.getStripeConnectedAccountCountry());
        info.put("bankName", wallet.getStripeExternalBankName());
        info.put("bankLast4", wallet.getStripeExternalBankLast4());
        return info;
    }

    private WalletResponse mapToResponse(Wallet wallet) {
        WalletResponse response = new WalletResponse();
        response.setId(wallet.getId());
        response.setAccountNumber(wallet.getAccountNumber());
        response.setUserId(wallet.getUserId());
        response.setBalance(wallet.getBalance());
        response.setCurrency(wallet.getCurrency());
        response.setFrozen(wallet.isFrozen());
        response.setPaypalEmail(wallet.getPaypalEmail());
        response.setMpesaPhoneNumber(wallet.getMpesaPhoneNumber());
        response.setPochiPhoneNumber(wallet.getPochiPhoneNumber());
        response.setHasSavedCard(wallet.getStripeDefaultPaymentMethodId() != null);
        response.setCardBrand(wallet.getStripeCardBrand());
        response.setCardLast4(wallet.getStripeCardLast4());
        response.setHasPaypalConnected(wallet.getPaypalVaultId() != null);
        response.setPaypalConnectedEmail(wallet.getPaypalConnectedEmail());
        return response;
    }
}