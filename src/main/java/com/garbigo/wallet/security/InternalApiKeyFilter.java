package com.garbigo.wallet.security;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates internal, service-to-service requests under /internal/**
 * using the shared API key instead of a user JWT. This is how other Garbigo
 * services call wallet-service without a user's own token.
 *
 * The header is X-Internal-Api-Key, the same one the Garbigo auth service uses
 * (its own InternalApiKeyFilter). The legacy X-API-Key header is still accepted
 * so existing callers keep working.
 *
 * Only runs for /internal/**; every other route needs a normal JWT via
 * JwtAuthenticationFilter.
 */
@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String LEGACY_API_KEY_HEADER = "X-API-Key";

    @Value("${internal.api-key:}")
    private String expectedApiKey;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getServletPath().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (expectedApiKey == null || expectedApiKey.isBlank()) {
            throw new AuthenticationServiceException(
                    "internal.api-key is not configured on wallet-service, refusing all /internal/** calls");
        }

        String providedKey = request.getHeader(API_KEY_HEADER);
        if (providedKey == null) {
            providedKey = request.getHeader(LEGACY_API_KEY_HEADER);
        }

        if (providedKey != null && MessageDigest.isEqual(
                providedKey.getBytes(StandardCharsets.UTF_8),
                expectedApiKey.getBytes(StandardCharsets.UTF_8))) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}