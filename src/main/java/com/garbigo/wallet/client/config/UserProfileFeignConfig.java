package com.premisave.wallet.client.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign config for user-facing auth-service endpoints (social, profile, etc.).
 * Forwards the caller's JWT as-is — auth service will validate it normally.
 *
 * Deliberately separate from AuthServiceFeignConfig (API key) so the two
 * credential strategies never mix.
 */
@Configuration
public class UserProfileFeignConfig {

    @Bean
    RequestInterceptor jwtForwardingInterceptor() {
        return template -> {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String authHeader = attrs.getRequest().getHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    template.header("Authorization", authHeader);
                }
            }
        };
    }
}