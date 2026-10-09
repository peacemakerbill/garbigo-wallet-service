package com.premisave.wallet.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates internal, service-to-service requests under /internal/**
 * using a shared API key (X-API-Key header) instead of a tenant/owner JWT.
 * This is how other Premisave services (e.g. property-service, see
 * WalletServiceFeignConfig) call wallet-service without a user's own token.
 *
 * Only runs for /internal/** — every other route continues to require a
 * normal JWT via JwtAuthenticationFilter.
 */
@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

    @Value("${internal.api-key:}")
    private String expectedApiKey;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getServletPath().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String providedKey = request.getHeader(API_KEY_HEADER);

        if (expectedApiKey == null || expectedApiKey.isBlank()) {
            throw new AuthenticationServiceException(
                    "internal.api-key is not configured on wallet-service — refusing all /internal/** calls");
        }

        if (providedKey != null && providedKey.equals(expectedApiKey)) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}