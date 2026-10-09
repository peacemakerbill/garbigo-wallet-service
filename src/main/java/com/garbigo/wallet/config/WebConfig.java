package com.premisave.wallet.config;

import com.premisave.wallet.util.RateLimiterInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimiterInterceptor rateLimiterInterceptor;

    public WebConfig(RateLimiterInterceptor rateLimiterInterceptor) {
        this.rateLimiterInterceptor = rateLimiterInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimiterInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                    "/system/health",
                    "/system/health/details",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    // Payment provider callbacks should bypass rate limiting
                    // — they come from Safaricom/Stripe/PayPal servers, not end users.
                    // NOTE: "/payments/stk-callback" and "/payments/c2b-validation" /
                    // "/payments/c2b-confirmation" deliberately have no "mpesa" in
                    // them — Safaricom's sandbox rejects CallBackURL/ValidationURL/
                    // ConfirmationURL values containing that word with
                    // 400.002.02 "Invalid CallBackURL".
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
    }
}