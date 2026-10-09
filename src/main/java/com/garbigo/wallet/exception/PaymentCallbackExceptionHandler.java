package com.premisave.wallet.exception;

import com.premisave.wallet.controller.MpesaC2BCallbackController;
import com.premisave.wallet.controller.PaymentCallbackController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Scoped exclusively to the two controllers that receive payment-provider
 * webhooks/callbacks (Safaricom, Stripe, PayPal) via assignableTypes — NOT
 * a global handler. Malformed JSON hitting our own internal/admin APIs
 * still surfaces as a normal 400 to that caller; this only softens the
 * failure mode for external providers we don't control the payload of.
 *
 * Added after a schema mismatch between Safaricom's real B2C/B2Pochi
 * ResultURL payload and MpesaResultCallbackRequest caused Jackson to reject
 * the request with HttpMessageNotReadableException before
 * PaymentCallbackController's own try/catch (which only starts inside the
 * method body, after argument binding) ever ran. The default 400 response
 * for that exception meant Safaricom retried indefinitely, since these
 * providers only stop retrying on a 200. See MpesaResultCallbackRequest for
 * the actual DTO fix (ignoreUnknown) — this handler is the safety net for
 * if/when the payload shape drifts again in the future.
 *
 * See CallbackRequestLoggingFilter for how the raw offending body is
 * logged even when this fires.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = {PaymentCallbackController.class, MpesaC2BCallbackController.class})
public class PaymentCallbackExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableCallbackBody(HttpMessageNotReadableException ex) {
        log.error("Callback body failed to deserialize — see CallbackRequestLoggingFilter's raw body log line above", ex);

        // Always ACK 200 to the provider — same pattern already used by
        // MpesaC2BCallbackController.confirm and the PayPal webhook handler:
        // a non-200 here just causes indefinite retries instead of fixing anything.
        return ResponseEntity.ok(Map.of(
                "ResultCode", "0",
                "ResultDesc", "Accepted"
        ));
    }
}