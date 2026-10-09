package com.premisave.wallet.exception;

import com.premisave.wallet.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleWalletNotFound(WalletNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Generic "id doesn't exist" case, used across the reconciliation
     * feature (and any future lookup-by-id) — a clean 404 with the real
     * message instead of falling through to the generic Exception
     * handler below, which is what produced a raw "An unexpected error
     * occurred" 500 for what is genuinely just a wrong/made-up id.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInsufficientFunds(InsufficientFundsException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(WalletFrozenException.class)
    public ResponseEntity<ApiResponse<Void>> handleWalletFrozen(WalletFrozenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Wallet already exists (POST /wallet/create called twice). 409 Conflict
     * is the more semantically correct code here — unlike WalletFrozenException
     * (an action forbidden by current *state*), this is a resource-already-exists
     * conflict, the same category as DuplicateTransactionException below.
     */
    @ExceptionHandler(WalletAlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleWalletAlreadyExists(WalletAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Freeze/unfreeze called when the wallet is already in that state.
     * 409 Conflict — the request conflicts with the wallet's current state,
     * same category as WalletAlreadyExistsException above.
     */
    @ExceptionHandler(WalletAlreadyFrozenException.class)
    public ResponseEntity<ApiResponse<Void>> handleWalletAlreadyFrozen(WalletAlreadyFrozenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(WalletNotFrozenException.class)
    public ResponseEntity<ApiResponse<Void>> handleWalletNotFrozen(WalletNotFrozenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(DuplicateTransactionException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateTransaction(DuplicateTransactionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * A wallet's M-Pesa number is already registered to a different wallet
     * (PUT /wallet/mpesa-phone). 409 Conflict — same "resource-already-exists
     * under this key" category as WalletAlreadyExistsException /
     * DuplicateTransactionException above, not a validation failure.
     */
    @ExceptionHandler(DuplicateMpesaPhoneNumberException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateMpesaPhoneNumber(DuplicateMpesaPhoneNumberException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(PhoneNumberUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handlePhoneNumberUnavailable(PhoneNumberUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(PaypalCaptureException.class)
    public ResponseEntity<ApiResponse<Void>> handlePaypalCapture(PaypalCaptureException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = ((FieldError) error).getField();
            errors.put(field, error.getDefaultMessage());
        });
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Validation failed", errors));
    }

    /**
     * Catches a malformed @RequestParam/@PathVariable value BEFORE it ever
     * reaches a controller method — e.g. ?date=2026 or ?date=2026-08-8
     * against a LocalDate parameter (missing zero-padding on the day),
     * or an invalid string against an enum-typed filter like
     * DepositStatus/DisbursementStatus/TransferStatus/PaymentStatus.
     * Previously fell all the way through to the generic Exception
     * handler below, surfacing as a bare 500 with a full stack trace in
     * the logs for what is genuinely just bad user input, not a system
     * fault — same category of fix as C2BUrlsAlreadyRegisteredException
     * earlier.
     *
     * Deliberately GENERIC, not scoped to any one endpoint — this fires
     * for any typed @RequestParam anywhere in the app, including every
     * status/fromDate/toDate/direction filter added across the Deposit/
     * Disbursement/Transfer/Payment history endpoints, plus the existing
     * admin transaction/report filters.
     *
     * For an enum target type specifically, the message lists the actual
     * valid values (via getEnumConstants()) rather than just saying
     * "invalid" — turns a dead end into something the caller can
     * immediately act on without needing to check the API docs.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        Object rawValue = ex.getValue();
        Class<?> requiredType = ex.getRequiredType();

        String message;
        if (requiredType != null && requiredType.isEnum()) {
            String validValues = java.util.Arrays.stream(requiredType.getEnumConstants())
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.joining(", "));
            message = "Invalid value '" + rawValue + "' for parameter '" + paramName
                    + "' — expected one of: " + validValues;
        } else if (requiredType == java.time.LocalDate.class) {
            message = "Invalid date '" + rawValue + "' for parameter '" + paramName
                    + "' — expected format: yyyy-MM-dd (e.g. 2026-08-19)";
        } else {
            message = "Invalid value '" + rawValue + "' for parameter '" + paramName + "'";
        }

        log.warn("Request parameter type mismatch: parameter={} value={} requiredType={}",
                paramName, rawValue, requiredType != null ? requiredType.getSimpleName() : "unknown");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(message));
    }

    /**
     * Catches a request body that fails to deserialize at all — e.g. a
     * field typed too narrowly for what the sender actually puts in it.
     * Concrete case that surfaced this gap: Safaricom's M-Pesa ResultURL
     * callback can send an ALPHANUMERIC ResultCode ("TP40153" — a
     * permission-denied error) even though most of the time it's a plain
     * integer ("0" for success). If the receiving DTO field is typed as
     * int/Integer, Jackson throws InvalidFormatException wrapped in this
     * exception — which previously fell all the way through to the
     * generic Exception handler below, producing a full stack trace in
     * the logs for what is a real Safaricom response, not a system fault.
     *
     * NOTE: for a webhook/callback endpoint specifically (M-Pesa
     * ResultURL, Stripe/PayPal/Flutterwave/NOWPayments webhooks), it's
     * not fully confirmed whether 400 is the ideal response here versus
     * risking a retry loop if the calling provider treats any non-2xx as
     * "retry this same payload" — the payload shape won't change between
     * retries, so that would just repeat the same failure indefinitely.
     * This is a genuine improvement over today regardless (clean log,
     * proper status, no stack trace spam) — but the exact status code for
     * webhook paths specifically may be worth revisiting with visibility
     * into how each provider's retry behavior actually works.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        String message = "Request body could not be parsed";
        Throwable cause = ex.getCause();
        if (cause instanceof tools.jackson.databind.exc.InvalidFormatException ife) {
            String field = ife.getPath().isEmpty() ? "unknown"
                    : ife.getPath().get(ife.getPath().size() - 1).getPropertyName();
            message = "Invalid value '" + ife.getValue() + "' for field '" + field
                    + "' — expected " + ife.getTargetType().getSimpleName();
        }

        log.warn("Unreadable request body: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(message));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Access denied"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Authentication required"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Thrown by service methods rejecting an action because the wallet isn't
     * in a state that supports it right now — e.g. DELETE /wallet/stripe/card
     * or DELETE /wallet/paypal/disconnect when nothing is actually linked
     * (DepositService.removeSavedCard, WalletService.disconnectPaypalAccount,
     * WalletService.disconnectStripeConnectAccount), or re-linking a PayPal
     * account when one is already attached (DepositService.createPaypalLinkToken).
     *
     * 409 Conflict — same "request conflicts with current resource state"
     * category as WalletAlreadyExistsException / WalletAlreadyFrozenException /
     * WalletNotFrozenException above; not worth a dedicated exception type
     * per case the way those have. Without this handler, IllegalStateException
     * fell through to handleGeneric below and returned a useless "An
     * unexpected error occurred" with a 500, discarding the specific,
     * actionable message the service layer had already worked out (e.g.
     * "No saved card is linked to this wallet.").
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Thrown by MpesaC2BService.parseRegisterUrlsResponse when Safaricom
     * rejects a Register URL call because URLs are already on file for
     * the shortcode (errorCode 500.003.1001, "Duplicate notification
     * info"). Same reasoning as handleIllegalState above — a real,
     * well-understood business condition, not a system fault, so it gets
     * its own 409 with the actual actionable message instead of falling
     * through to handleGeneric's bare 500 "An unexpected error occurred".
     */
    @ExceptionHandler(com.premisave.wallet.exception.C2BUrlsAlreadyRegisteredException.class)
    public ResponseEntity<ApiResponse<Void>> handleC2BUrlsAlreadyRegistered(
            com.premisave.wallet.exception.C2BUrlsAlreadyRegisteredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Thrown by PaymentCallbackController.stripeWebhook / stripeConnectWebhook
     * (via StripeService.constructWebhookEvent) when the Stripe-Signature
     * header doesn't match what's expected for the given secret. Centralized
     * here rather than caught locally in each webhook method, matching every
     * other exception in this file — also lets both endpoints share one
     * handler while still telling them apart via the request path, since the
     * fix differs slightly (STRIPE_WEBHOOK_SECRET vs STRIPE_CONNECT_WEBHOOK_SECRET).
     *
     * Expected, recoverable class of failure — a mismatched or stale secret,
     * usually from switching sandboxes or rotating webhook destinations. No
     * stack trace: the fix is always "update the secret to match this
     * destination's current signing secret," never a code change. Stripe
     * retries automatically once corrected, so a 400 here is safe.
     */
    @ExceptionHandler(com.stripe.exception.SignatureVerificationException.class)
    public ResponseEntity<ApiResponse<Void>> handleStripeSignatureVerification(
            com.stripe.exception.SignatureVerificationException ex, HttpServletRequest request) {
        String envVarHint = request.getRequestURI().contains("/connect/webhook")
                ? "STRIPE_CONNECT_WEBHOOK_SECRET matches the Connect Webhook destination's"
                : "STRIPE_WEBHOOK_SECRET matches the Platform Webhook destination's";
        log.warn("Stripe webhook signature verification failed at {} — check {} current signing secret",
                request.getRequestURI(), envVarHint);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Invalid webhook signature"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred"));
    }
}