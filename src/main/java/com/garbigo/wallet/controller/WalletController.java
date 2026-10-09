package com.premisave.wallet.controller;

import com.premisave.wallet.dto.*;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.TransferStatus;
import com.premisave.wallet.repository.WalletRepository;
import com.premisave.wallet.service.DepositService;
import com.premisave.wallet.service.FlutterwaveDepositService;
import com.premisave.wallet.service.FlutterwaveService;
import com.premisave.wallet.service.PaypalDepositService;
import com.premisave.wallet.service.StripeDepositService;
import com.premisave.wallet.service.TransferService;
import com.premisave.wallet.service.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final TransferService transferService;
    private final DepositService depositService;
    private final StripeDepositService stripeDepositService;
    private final PaypalDepositService paypalDepositService;
    private final FlutterwaveDepositService flutterwaveDepositService;
    private final FlutterwaveService flutterwaveService;
    private final WalletRepository walletRepository;

    /**
     * Resolves the caller's real userId from the request attribute stashed
     * by JwtAuthenticationFilter (populated from the JWT "userId" claim).
     *
     * IMPORTANT: this is intentionally NOT auth.getName() — that returns
     * UserDetails.username, which UserDetailsServiceImpl sets to the JWT
     * "sub" (email) claim. Wallet.userId is a separate real identifier
     * (distinct from Wallet.accountNumber, which IS the email and comes
     * from auth-service's user profile). Every method below that looks up
     * a wallet by userId must use this helper; methods that look up a
     * wallet by accountNumber/email must keep using auth.getName().
     */
    private String resolveUserId(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException(
                    "Authenticated request is missing a userId claim — token may be malformed or missing 'userId'.");
        }
        return userId;
    }

    // ─── Endpoints keyed by accountNumber (email) ────────────────

    @GetMapping
    public ResponseEntity<ApiResponse<WalletResponse>> getMyWallet(Authentication auth) {
        String email = auth.getName();
        return ResponseEntity.ok(ApiResponse.success("Wallet retrieved", walletService.getWallet(email)));
    }

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<WalletBalanceResponse>> getBalance(Authentication auth) {
        String email = auth.getName();
        return ResponseEntity.ok(ApiResponse.success("Balance retrieved", walletService.getBalance(email)));
    }

    @PostMapping("/statement")
    public ResponseEntity<ApiResponse<WalletStatementResponse>> getStatement(
            @Valid @RequestBody WalletStatementRequest request,
            Authentication auth) {
        String email = auth.getName();
        WalletStatementResponse statement = walletService.getStatement(email, request);
        return ResponseEntity.ok(ApiResponse.success("Wallet statement retrieved successfully", statement));
    }

    // ─── Endpoints keyed by real userId — ───────────────────────────────

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<WalletResponse>> createWallet(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        String email = auth.getName();
        WalletResponse wallet = walletService.createWallet(userId, email);
        return ResponseEntity.ok(ApiResponse.success("Wallet created", wallet));
    }

    @PostMapping("/deposit")
    public ResponseEntity<ApiResponse<PaymentResponse>> deposit(
            @Valid @RequestBody DepositRequest depositRequest,
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        String email = auth.getName();
        PaymentResponse response = depositService.initiateDeposit(userId, email, depositRequest);
        return ResponseEntity.ok(ApiResponse.success("Deposit initiated", response));
    }

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<PaymentResponse>> transfer(
            @Valid @RequestBody TransferRequest transferRequest,
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        PaymentResponse response = transferService.transfer(userId, transferRequest);
        return ResponseEntity.ok(ApiResponse.success("Transfer successful", response));
    }

    /**
     * Every transfer where the authenticated user is EITHER the sender or
     * the recipient, newest first — each record's "direction" field
     * ("SENT"/"RECEIVED") tells you which side you're looking at.
     * GET /wallet/transfer/history
     */
    @GetMapping("/transfer/history")
    public ResponseEntity<ApiResponse<List<TransferRecordResponse>>> getTransferHistory(
            @RequestParam(required = false) TransferStatus status,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        List<TransferRecordResponse> history = transferService.getTransferHistory(userId, status, direction, fromDate, toDate);
        return ResponseEntity.ok(ApiResponse.success("Transfer history retrieved", history));
    }

    @PutMapping("/freeze")
    public ResponseEntity<ApiResponse<WalletResponse>> freeze(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        return ResponseEntity.ok(ApiResponse.success("Wallet frozen", walletService.freezeWallet(userId)));
    }

    @PutMapping("/unfreeze")
    public ResponseEntity<ApiResponse<WalletResponse>> unfreeze(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        return ResponseEntity.ok(ApiResponse.success("Wallet unfrozen", walletService.unfreezeWallet(userId)));
    }

    @PutMapping("/paypal-email")
    public ResponseEntity<ApiResponse<WalletResponse>> updatePaypalEmail(
            @Valid @RequestBody UpdatePaypalEmailRequest updateRequest,
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        WalletResponse response = walletService.updatePaypalEmail(userId, updateRequest.getPaypalEmail());
        return ResponseEntity.ok(ApiResponse.success("PayPal email updated", response));
    }

    @PutMapping("/paypal/disconnect")
    public ResponseEntity<ApiResponse<WalletResponse>> disconnectPaypal(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        WalletResponse response = walletService.disconnectPaypalAccount(userId);
        return ResponseEntity.ok(ApiResponse.success(
                "PayPal account unlinked. Note: this does not revoke access on PayPal's side.", response));
    }

    @PostMapping("/paypal/link")
    public ResponseEntity<ApiResponse<Map<String, String>>> createPaypalLink(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        Map<String, String> result = paypalDepositService.createPaypalLinkToken(userId);
        return ResponseEntity.ok(ApiResponse.success("PayPal link initiated", result));
    }

    @PostMapping("/paypal/link/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmPaypalLink(
            @RequestBody Map<String, String> body,
            Authentication auth, HttpServletRequest request) {
        String setupTokenId = body.get("setupTokenId");
        if (setupTokenId == null || setupTokenId.isBlank()) {
            throw new IllegalArgumentException("setupTokenId is required");
        }
        String userId = resolveUserId(request);
        paypalDepositService.confirmPaypalLink(setupTokenId, userId);
        return ResponseEntity.ok(ApiResponse.success("PayPal account linked"));
    }

    @GetMapping("/paypal/account")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPaypalAccount(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);

        var walletOpt = walletRepository.findByUserId(userId);
        Map<String, Object> info = new HashMap<>();

        if (walletOpt.isPresent()) {
            Wallet wallet = walletOpt.get();
            info.put("linked", wallet.getPaypalVaultId() != null);
            info.put("email", wallet.getPaypalConnectedEmail());
            info.put("vaultId", wallet.getPaypalVaultId());
            info.put("customerId", wallet.getPaypalCustomerId());
        } else {
            info.put("linked", false);
        }

        return ResponseEntity.ok(ApiResponse.success("PayPal account status retrieved", info));
    }

    @PutMapping("/mpesa-phone")
    public ResponseEntity<ApiResponse<WalletResponse>> updateMpesaPhone(
            @Valid @RequestBody UpdateMpesaPhoneRequest updateRequest,
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        WalletResponse response = walletService.updateMpesaPhoneNumber(userId, updateRequest.getMpesaPhoneNumber());
        return ResponseEntity.ok(ApiResponse.success("M-Pesa phone number updated", response));
    }

    @PutMapping("/pochi-phone")
    public ResponseEntity<ApiResponse<WalletResponse>> updatePochiPhone(
            @Valid @RequestBody UpdatePochiPhoneRequest updateRequest,
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        WalletResponse response = walletService.updatePochiPhoneNumber(userId, updateRequest.getPochiPhoneNumber());
        return ResponseEntity.ok(ApiResponse.success("Pochi phone number updated", response));
    }

    @PostMapping("/stripe/setup-intent")
    public ResponseEntity<ApiResponse<Map<String, String>>> createStripeSetupIntent(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        String email = auth.getName();
        Map<String, String> result = stripeDepositService.createStripeSetupIntent(userId, email);
        return ResponseEntity.ok(ApiResponse.success("Setup intent created", result));
    }

    @PostMapping("/stripe/setup-intent/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmStripeSetupIntent(
            @RequestBody Map<String, String> body,
            Authentication auth, HttpServletRequest request) {
        String setupIntentId = body.get("setupIntentId");
        if (setupIntentId == null || setupIntentId.isBlank()) {
            throw new IllegalArgumentException("setupIntentId is required");
        }
        String userId = resolveUserId(request);
        stripeDepositService.confirmStripeSetupIntent(setupIntentId, userId);
        return ResponseEntity.ok(ApiResponse.success("Card saved"));
    }

    /**
     * Lists every saved card for this wallet, newest first. Distinct from
     * hasSavedCard/cardBrand/cardLast4 on the main GET /wallet response,
     * which only reflects whichever card is currently the default — this
     * is the full set, for a "manage payment methods" screen.
     * GET /wallet/stripe/cards
     */
    @GetMapping("/stripe/cards")
    public ResponseEntity<ApiResponse<List<SavedCardResponse>>> listStripeSavedCards(
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        List<SavedCardResponse> response = stripeDepositService.listSavedCards(userId).stream()
                .map(c -> new SavedCardResponse(c.getStripePaymentMethodId(), c.getBrand(), c.getLast4(), c.isDefault()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success("Saved cards retrieved", response));
    }

    /**
     * Removes one specific saved card. If it was the default, the
     * next-most-recent remaining card is promoted automatically.
     * DELETE /wallet/stripe/cards/{paymentMethodId}
     */
    @DeleteMapping("/stripe/cards/{paymentMethodId}")
    public ResponseEntity<ApiResponse<Void>> removeStripeSavedCard(
            @PathVariable String paymentMethodId, Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        stripeDepositService.removeSavedCard(userId, paymentMethodId);
        return ResponseEntity.ok(ApiResponse.success("Saved card removed"));
    }

    /**
     * Catches DELETE /wallet/stripe/cards or /wallet/stripe/cards/ — i.e.
     * paymentMethodId was omitted or resolved to an empty string (e.g. an
     * unset Postman variable). Spring can't route a request like this to
     * removeStripeSavedCard above, since that mapping's {paymentMethodId}
     * segment requires a non-empty value — without this companion mapping,
     * the request falls through to Spring's static-resource handler and
     * surfaces as a generic 500 instead of a clear validation error.
     */
    @DeleteMapping({"/stripe/cards", "/stripe/cards/"})
    public ResponseEntity<ApiResponse<Void>> removeStripeSavedCardMissingId() {
        throw new IllegalArgumentException(
                "paymentMethodId is required — DELETE /wallet/stripe/cards/{paymentMethodId}");
    }

    /**
     * Switches which saved card future deposits charge. App-level concept
     * only — no Stripe call involved.
     * PUT /wallet/stripe/cards/{paymentMethodId}/default
     */
    @PutMapping("/stripe/cards/{paymentMethodId}/default")
    public ResponseEntity<ApiResponse<Void>> setDefaultStripeSavedCard(
            @PathVariable String paymentMethodId, Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        stripeDepositService.setDefaultSavedCard(userId, paymentMethodId);
        return ResponseEntity.ok(ApiResponse.success("Default card updated"));
    }

    /**
     * Same reasoning as removeStripeSavedCardMissingId above — catches
     * PUT /wallet/stripe/cards/default (paymentMethodId segment collapsed
     * away entirely) with a clear validation error instead of a 500.
     */
    @PutMapping("/stripe/cards/default")
    public ResponseEntity<ApiResponse<Void>> setDefaultStripeSavedCardMissingId() {
        throw new IllegalArgumentException(
                "paymentMethodId is required — PUT /wallet/stripe/cards/{paymentMethodId}/default");
    }

    // ─── Stripe Connect (bank withdrawal linking) ───────────────────────────
    // Distinct from /wallet/stripe/setup-intent above, which saves a CARD
    // for deposits. This links an external bank account (via Stripe Connect
    // Express onboarding) for withdrawals — see DisbursementService's
    // STRIPE branch. International users only (US/UK/EU banks); Kenya stays
    // on M-Pesa/Flutterwave.

    /**
     * Starts (or resumes) Stripe Connect onboarding. Returns a single-use
     * onboardingUrl — redirect the user to it immediately.
     * POST /wallet/stripe/connect/link
     */
    @PostMapping("/stripe/connect/link")
    public ResponseEntity<ApiResponse<Map<String, String>>> linkStripeConnectAccount(
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        String email = auth.getName();
        Map<String, String> result = stripeDepositService.createStripeConnectLink(userId, email);
        return ResponseEntity.ok(ApiResponse.success("Stripe Connect onboarding started", result));
    }

    /**
     * Unlinks the wallet's Stripe Connect account. See WalletService.
     * disconnectStripeConnectAccount for why this doesn't (and can't)
     * revoke anything on Stripe's own side.
     * DELETE /wallet/stripe/connect/link
     */
    @DeleteMapping("/stripe/connect/link")
    public ResponseEntity<ApiResponse<WalletResponse>> unlinkStripeConnectAccount(
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        WalletResponse response = walletService.disconnectStripeConnectAccount(userId);
        return ResponseEntity.ok(ApiResponse.success(
                "Stripe bank account unlinked. Future withdrawal requests will need a new link.", response));
    }

    /**
     * Read-only cached status (linked/payoutsEnabled/bank display info),
     * kept in sync by the account.updated Connect webhook — same
     * "cached, webhook-synced" pattern as getPaypalAccount/getFlutterwaveAccount.
     * GET /wallet/stripe/connect/account
     */
    @GetMapping("/stripe/connect/account")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStripeConnectAccount(
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);

        var walletOpt = walletRepository.findByUserId(userId);
        Map<String, Object> info = new HashMap<>();

        if (walletOpt.isPresent()) {
            Wallet wallet = walletOpt.get();
            info.put("linked", wallet.getStripeConnectedAccountId() != null);
            info.put("accountId", wallet.getStripeConnectedAccountId());
            info.put("payoutsEnabled", wallet.isStripePayoutsEnabled());
            info.put("country", wallet.getStripeConnectedAccountCountry());
            info.put("bankName", wallet.getStripeExternalBankName());
            info.put("bankLast4", wallet.getStripeExternalBankLast4());
        } else {
            info.put("linked", false);
        }

        return ResponseEntity.ok(ApiResponse.success("Stripe Connect account status retrieved", info));
    }

    /**
     * Actively queries Stripe for this account's current status rather than
     * waiting on the webhook — call right after the user returns from
     * onboarding (Stripe's return_url) so the UI reflects reality
     * immediately instead of showing stale "not enabled" until the webhook
     * arrives (which can lag, or be missed if the Connect webhook endpoint
     * isn't fully set up yet).
     * POST /wallet/stripe/connect/refresh
     */
    @PostMapping("/stripe/connect/refresh")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshStripeConnectStatus(
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        Map<String, Object> info = walletService.refreshStripeConnectStatus(userId);
        return ResponseEntity.ok(ApiResponse.success("Stripe Connect status refreshed", info));
    }

    @PostMapping("/deposit/paypal/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmPaypalDeposit(
            @RequestBody Map<String, String> body,
            Authentication auth, HttpServletRequest request) {
        String orderId = body.get("orderId");
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }
        String userId = resolveUserId(request);
        PaymentResponse response = paypalDepositService.confirmPaypalDeposit(orderId, userId);
        return ResponseEntity.ok(ApiResponse.success("PayPal deposit processed", response));
    }

    @PostMapping("/deposit/stripe/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmStripeDeposit(
            @RequestBody Map<String, String> body,
            Authentication auth, HttpServletRequest request) {
        String paymentIntentId = body.get("paymentIntentId");
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new IllegalArgumentException("paymentIntentId is required");
        }
        String userId = resolveUserId(request);
        PaymentResponse response = stripeDepositService.confirmStripeDeposit(paymentIntentId, userId);
        return ResponseEntity.ok(ApiResponse.success("Stripe deposit processed", response));
    }

    @PostMapping("/deposit/flutterwave/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmFlutterwaveDeposit(
            @RequestBody Map<String, String> body,
            Authentication auth, HttpServletRequest request) {
        String txRef = body.get("txRef");
        if (txRef == null || txRef.isBlank()) {
            throw new IllegalArgumentException("txRef is required");
        }
        String userId = resolveUserId(request);
        PaymentResponse response = flutterwaveDepositService.confirmFlutterwaveDeposit(txRef, userId);
        return ResponseEntity.ok(ApiResponse.success("Flutterwave deposit processed", response));
    }

    @PostMapping("/flutterwave/link")
    public ResponseEntity<ApiResponse<Map<String, String>>> linkFlutterwaveAccount(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);

        walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for userId: " + userId));

        log.info("Flutterwave account linking initiated: userId={}", userId);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Flutterwave account linking initiated");

        return ResponseEntity.ok(ApiResponse.success("Flutterwave linking initiated", response));
    }

    @PostMapping("/flutterwave/link/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmFlutterwaveLink(
            @RequestBody Map<String, String> body,
            Authentication auth, HttpServletRequest request) {
        String customerId = body.get("customerId");
        String paymentMethodId = body.get("paymentMethodId");
        String network = body.get("network");
        String phone = body.get("phone");

        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }

        String userId = resolveUserId(request);

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for userId: " + userId));

        flutterwaveService.linkFlutterwaveAccount(wallet, customerId, paymentMethodId, network, phone);
        walletRepository.save(wallet);

        log.info("Flutterwave account linked: userId={} customerId={}", userId, customerId);

        return ResponseEntity.ok(ApiResponse.success("Flutterwave account linked successfully"));
    }

    @DeleteMapping("/flutterwave/link")
    public ResponseEntity<ApiResponse<Void>> unlinkFlutterwaveAccount(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for userId: " + userId));

        flutterwaveService.unlinkAccount(wallet);
        walletRepository.save(wallet);

        log.info("Flutterwave account unlinked: userId={}", userId);

        return ResponseEntity.ok(ApiResponse.success("Flutterwave account unlinked"));
    }

    @GetMapping("/flutterwave/account")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFlutterwaveAccount(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);

        var walletOpt = walletRepository.findByUserId(userId);
        Map<String, Object> info = new HashMap<>();

        if (walletOpt.isPresent()) {
            Wallet wallet = walletOpt.get();
            info.put("linked", wallet.getFlutterwaveCustomerId() != null);
            info.put("customerId", wallet.getFlutterwaveCustomerId());
            info.put("paymentMethodId", wallet.getFlutterwavePaymentMethodId());
            info.put("network", wallet.getFlutterwavePaymentMethodNetwork());
            info.put("phone", wallet.getFlutterwavePaymentMethodPhone());
        } else {
            info.put("linked", false);
        }

        return ResponseEntity.ok(ApiResponse.success("Flutterwave account status retrieved", info));
    }
}