package com.premisave.wallet.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Logs the raw request body for every payment-provider callback endpoint,
 * BEFORE Jackson attempts to deserialize it into a DTO.
 *
 * Added after an incident where B2C/B2Pochi ResultURL callbacks were
 * confirmed (via the zrok tunnel logs) to be reaching this service, but
 * never appeared in application logs at all — not even the controller's
 * first log.info line. Root cause was a schema mismatch in
 * MpesaResultCallbackRequest (see that class) that made Jackson reject the
 * whole request with HttpMessageNotReadableException before the controller
 * method — and therefore its logging — ever ran. Spring MVC reads the
 * request body directly off the raw InputStream, so by the time an
 * exception handler catches a deserialization failure, the body is already
 * consumed and unavailable unless it was cached up front — hence this
 * filter wrapping the request in a ContentCachingRequestWrapper.
 *
 * This filter exists so that class of failure (a provider changing its
 * payload shape) is never silent again, independent of whether
 * deserialization downstream succeeds or fails.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CallbackRequestLoggingFilter extends OncePerRequestFilter {

    // Keep in sync with the public callback matchers in SecurityConfig / WebConfig.
    private static final List<String> CALLBACK_PATHS = List.of(
            "/payments/stk-callback",
            "/payments/c2b-validation",
            "/payments/c2b-confirmation",
            "/payments/mpesa/b2c/result",
            "/payments/mpesa/b2c/timeout",
            "/payments/mpesa/b2b/result",
            "/payments/mpesa/b2b/timeout",
            "/payments/mpesa/b2b/express-checkout/result",
            "/payments/mpesa/balance/result",
            "/payments/mpesa/balance/timeout",
            "/payments/mpesa/transactionstatus/result",
            "/payments/mpesa/transactionstatus/timeout",
            "/payments/mpesa/reversal/result",
            "/payments/mpesa/reversal/timeout",
            "/payments/mpesa/b2pochi/result",
            "/payments/mpesa/b2pochi/timeout",
            "/payments/mpesa/pull/callback",
            "/payments/stripe/webhook",
            "/payments/stripe/connect/webhook",
            "/payments/paypal/webhook",
            "/payments/flutterwave/webhook",
            "/payments/nowpayments/webhook"
    );

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !CALLBACK_PATHS.contains(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, 0);

        try {
            filterChain.doFilter(wrappedRequest, response);
        } finally {
            byte[] body = wrappedRequest.getContentAsByteArray();
            String rawBody = body.length > 0 ? new String(body, StandardCharsets.UTF_8) : "<empty>";
            log.info("Callback raw body: path={} status={} body={}",
                    request.getServletPath(), response.getStatus(), rawBody);
        }
    }
}