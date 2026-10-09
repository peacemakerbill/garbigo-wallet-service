package com.premisave.wallet.client.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign configuration for the Auth Service client.
 * Injects the X-API-Key header on every outbound request so the
 * auth service's ApiKeyFilter accepts the call.
 *
 * Reads internal.api-key directly — previously read a separate
 * auth.service.api-key property (backed by its own AUTH_SERVICE_API_KEY
 * env var), which has been consolidated away: one shared API key
 * (INTERNAL_API_KEY) used across every microservice relationship now,
 * rather than a distinct secret per service-to-service pairing. The
 * yml's own auth.service block still exists for auth.service.url (a
 * genuinely different concern — where to send the request, not which
 * key to send with it), but no longer has its own api-key property at
 * all.
 *
 * Wired via the @FeignClient(configuration = ...) attribute —
 * NOT registered as a global @Configuration to avoid applying to
 * other Feign clients if you add more later.
 */
@Configuration
public class AuthServiceFeignConfig {

    @Value("${internal.api-key}")
    private String apiKey;

    @Bean
    RequestInterceptor apiKeyInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                template.header("X-API-Key", apiKey);
            }
        };
    }
}