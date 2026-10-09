package com.premisave.wallet.service;

import com.premisave.wallet.dto.DepositRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DepositStatus;
import com.premisave.wallet.exception.PaypalCaptureException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DepositRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * PayPal deposit business logic — order creation, vault-based account
 * linking, capture, and webhook reconciliation. Split out of the former
 * all-providers DepositService, mirroring PaypalService's existing role at
 * the API-integration layer.
 *
 * Migrated to the Deposit entity (Stage 3) — same pattern
 * NowPaymentsDepositService pioneered (Stage 2): a dedicated Deposit
 * record instead of a generic Transaction row with detail packed into a
 * free-text description, plus DepositTransactionRecorder creating the
 * matching Transaction row on confirmation for the unified history feed.
 *
 * Called from DepositService.initiateDeposit (dispatcher) for initiation,
 * and directly from WalletController/PaymentCallbackController for the
 * confirm endpoints and webhook handlers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaypalDepositService {

    private final WalletRepository walletRepository;
    private final DepositRepository depositRepository;
    private final PaypalService paypalService;
    private final DepositTransactionRecorder depositTransactionRecorder;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;

    public PaymentResponse initiatePaypalDeposit(String userId, DepositRequest request,
                                                  Wallet wallet, String idempotencyKey) {
        String requestedCurrency = request.getCurrency() != null ? request.getCurrency().toUpperCase() : "USD";
        if (!"USD".equals(requestedCurrency)) {
            throw new IllegalArgumentException("PayPal deposits must be in USD (got: " + requestedCurrency + ")");
        }

        BigDecimal usdAmount = request.getAmount();

        String existingVaultId = wallet.getPaypalVaultId();
        String existingCustomerId = wallet.getPaypalCustomerId();

        PaypalService.CreateOrderResult result = paypalService.createOrder(
                usdAmount, "USD", idempotencyKey, existingVaultId, existingCustomerId, true);

        String orderId = result.orderId();

        log.info("PayPal Order created: userId={} orderId={} usdAmount={} vaulted={} requiresAction={}",
                userId, orderId, usdAmount,
                existingVaultId != null, result.approveUrl() != null);

        Deposit deposit = new Deposit();
        deposit.setUserId(userId);
        deposit.setWalletId(wallet.getId());
        deposit.setAmount(usdAmount);
        deposit.setCurrency(Currency.USD);
        deposit.setProvider("PAYPAL");
        deposit.setChannel("PAYPAL_ORDER");
        deposit.setStatus(DepositStatus.PENDING);
        deposit.setReference(orderId);
        depositRepository.save(deposit);

        if (result.approveUrl() == null) {
            if ("COMPLETED".equals(result.status()) && result.captureId() != null) {
                PaypalService.CaptureResult captureResult = new PaypalService.CaptureResult(
                        result.captureId(), result.vaultId(), result.customerId(),
                        result.payerEmail(), result.vaultStatus());
                PaymentResponse credited = creditPaypalTransaction(deposit, captureResult);
                if (credited.isSuccess()) {
                    return new PaymentResponse(true, orderId,
                            "Deposit successful using your saved PayPal account.");
                }
                return credited;
            }

            PaymentResponse captured = confirmPaypalDepositInternal(deposit, orderId);
            if (captured.isSuccess()) {
                return new PaymentResponse(true, orderId,
                        "Deposit successful using your saved PayPal account.");
            }
            return captured;
        }

        return new PaymentResponse(true, result.approveUrl(),
                "Redirect the user to the PayPal approval URL to complete the deposit. "
                        + "USD " + usdAmount + " will be credited to your wallet.");
    }

    @Transactional
    public Map<String, String> createPaypalLinkToken(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found. Please create a wallet first."));

        if (wallet.getPaypalVaultId() != null) {
            throw new IllegalStateException(
                    "A PayPal account (" + wallet.getPaypalConnectedEmail() + ") is already linked to this wallet. "
                            + "Disconnect it first before linking a new one.");
        }

        PaypalService.SetupTokenResult result = paypalService.createSetupToken(userId);
        log.info("PayPal setup token created for account linking: userId={} setupTokenId={}", userId, result.setupTokenId());

        wallet.setPendingPaypalSetupTokenId(result.setupTokenId());
        walletRepository.save(wallet);

        return Map.of("setupTokenId", result.setupTokenId(), "approveUrl", result.approveUrl());
    }

    @Transactional
    public void confirmPaypalLink(String setupTokenId, String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.getPaypalVaultId() != null) {
            throw new IllegalStateException(
                    "A PayPal account (" + wallet.getPaypalConnectedEmail() + ") is already linked to this wallet. "
                            + "Disconnect it first before linking a new one.");
        }

        if (setupTokenId == null || !setupTokenId.equals(wallet.getPendingPaypalSetupTokenId())) {
            log.error("PayPal link confirm rejected — setupTokenId mismatch: walletId={} expected={} got={}",
                    wallet.getId(), wallet.getPendingPaypalSetupTokenId(), setupTokenId);
            throw new IllegalArgumentException("This PayPal setup token does not belong to the authenticated user");
        }

        PaypalService.LinkAccountResult result = paypalService.createPaymentTokenFromSetupToken(setupTokenId);

        wallet.setPaypalVaultId(result.vaultId());
        wallet.setPaypalCustomerId(result.customerId());
        wallet.setPaypalConnectedEmail(result.payerEmail());
        wallet.setPendingPaypalSetupTokenId(null);
        walletRepository.save(wallet);

        log.info("PayPal account linked: walletId={} vaultId={} customerId={} email={}",
                wallet.getId(), result.vaultId(), result.customerId(), result.payerEmail());
    }

    @Transactional
    public PaymentResponse confirmPaypalDeposit(String orderId) {
        Deposit deposit = depositRepository.findByReference(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "No pending deposit found for PayPal orderId=" + orderId));
        return confirmPaypalDepositInternal(deposit, orderId);
    }

    @Transactional
    public PaymentResponse confirmPaypalDeposit(String orderId, String callerUserId) {
        Deposit deposit = depositRepository.findByReference(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "No pending deposit found for PayPal orderId=" + orderId));

        if (!deposit.getUserId().equals(callerUserId)) {
            throw new IllegalArgumentException("This PayPal order does not belong to the authenticated user");
        }
        return confirmPaypalDepositInternal(deposit, orderId);
    }

    private PaymentResponse confirmPaypalDepositInternal(Deposit deposit, String orderId) {
        if (deposit.getStatus() == DepositStatus.SUCCESS) {
            log.info("PayPal deposit already processed for orderId={} — skipping duplicate capture", orderId);
            return new PaymentResponse(true, deposit.getId(), "Deposit already completed");
        }

        if (deposit.getStatus() == DepositStatus.FAILED) {
            return new PaymentResponse(false, deposit.getId(),
                    "This PayPal deposit previously failed and cannot be retried with the same order.");
        }

        PaypalService.CaptureResult captureResult;
        try {
            captureResult = paypalService.captureOrder(orderId);
        } catch (PaypalCaptureException e) {
            if (e.isAlreadyCaptured()) {
                log.warn("PayPal reports orderId={} already captured — reconciling from order details instead of failing",
                        orderId);
                try {
                    captureResult = paypalService.getOrder(orderId);
                } catch (Exception fetchEx) {
                    log.error("PayPal reports orderId={} already captured but reconciliation fetch failed — " +
                            "manual review required", orderId, fetchEx);
                    return new PaymentResponse(false, deposit.getId(),
                            "This deposit is in an inconsistent state and needs manual review. Please contact support.");
                }
            } else {
                markPaypalTransactionFailed(orderId, e.getMessage());
                return new PaymentResponse(false, deposit.getId(), "PayPal capture failed: " + e.getMessage());
            }
        } catch (Exception e) {
            markPaypalTransactionFailed(orderId, e.getMessage());
            return new PaymentResponse(false, deposit.getId(), "PayPal capture failed: " + e.getMessage());
        }

        return creditPaypalTransaction(deposit, captureResult);
    }

    private PaymentResponse creditPaypalTransaction(Deposit deposit, PaypalService.CaptureResult captureResult) {
        if (deposit.getStatus() == DepositStatus.SUCCESS) {
            log.info("PayPal deposit already processed for orderId={} — skipping duplicate credit", deposit.getReference());
            return new PaymentResponse(true, deposit.getId(), "Deposit already completed");
        }

        Wallet wallet = walletRepository.findById(deposit.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + deposit.getWalletId()));

        wallet.setBalance(wallet.getBalance().add(deposit.getAmount()));

        if (captureResult.vaultId() != null && !captureResult.vaultId().equals(wallet.getPaypalVaultId())) {
            wallet.setPaypalVaultId(captureResult.vaultId());
            if (captureResult.customerId() != null) {
                wallet.setPaypalCustomerId(captureResult.customerId());
            }
            if (captureResult.payerEmail() != null) {
                wallet.setPaypalConnectedEmail(captureResult.payerEmail());
            }
            log.info("PayPal account connected: walletId={} vaultId={} email={} status={}",
                    wallet.getId(), captureResult.vaultId(), captureResult.payerEmail(), captureResult.vaultStatus());
        }

        walletRepository.save(wallet);

        deposit.setStatus(DepositStatus.SUCCESS);
        deposit.setProviderReference(captureResult.captureId());
        String recipientName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
        deposit.setRecipientName(recipientName);
        depositRepository.save(deposit);

        depositTransactionRecorder.record(deposit.getUserId(), deposit.getWalletId(), deposit.getAmount(),
                deposit, deposit.getReference());

        emailService.sendDepositConfirmation(wallet.getAccountNumber(), deposit.getAmount().toPlainString(),
                deposit.getCurrency().name(), deposit.getReference(), wallet.getBalance().toPlainString(),
                new EmailService.DepositDetails("PayPal", null, captureResult.payerEmail(),
                        null, captureResult.captureId(), recipientName, wallet.getAccountNumber(), wallet.getId()));

        log.info("Wallet credited via PayPal: orderId={} amount={} captureId={}",
                deposit.getReference(), deposit.getAmount(), captureResult.captureId());
        return new PaymentResponse(true, deposit.getId(), "PayPal deposit successful");
    }

    @Transactional
    public void markPaypalTransactionFailed(String orderId, String reason) {
        depositRepository.findByReference(orderId).ifPresentOrElse(deposit -> {
            if (deposit.getStatus() == DepositStatus.SUCCESS) {
                log.warn("Ignoring failure callback for already-completed PayPal orderId={}", orderId);
                return;
            }
            deposit.setStatus(DepositStatus.FAILED);
            deposit.setFailureReason(reason);
            depositRepository.save(deposit);
            log.warn("PayPal deposit failed: orderId={} reason={}", orderId, reason);
        }, () -> log.warn("Failure callback for unknown PayPal orderId={}: {}", orderId, reason));
    }

    @Transactional
    public void attachPaypalVaultToken(String vaultId, String customerId, String payerEmail) {
        if (customerId == null) {
            log.warn("VAULT.PAYMENT-TOKEN.CREATED webhook missing customer.id — cannot resolve wallet for vaultId={}", vaultId);
            return;
        }
        walletRepository.findByPaypalCustomerId(customerId).ifPresentOrElse(wallet -> {
            wallet.setPaypalVaultId(vaultId);
            if (payerEmail != null) {
                wallet.setPaypalConnectedEmail(payerEmail);
            }
            walletRepository.save(wallet);
            log.info("PayPal vault token finalized via webhook: walletId={} vaultId={}", wallet.getId(), vaultId);
        }, () -> log.warn("VAULT.PAYMENT-TOKEN.CREATED webhook: no wallet found for customerId={}", customerId));
    }
}