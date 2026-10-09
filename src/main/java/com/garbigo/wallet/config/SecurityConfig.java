package com.premisave.wallet.config;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.security.InternalApiKeyFilter;
import com.premisave.wallet.security.JwtAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final InternalApiKeyFilter internalApiKeyFilter;
    private final JsonMapper objectMapper;

    @Value("${frontend.url:http://localhost:3000}")
    private String frontendUrl;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter, InternalApiKeyFilter internalApiKeyFilter,
                           JsonMapper objectMapper) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.internalApiKeyFilter = internalApiKeyFilter;
        this.objectMapper = objectMapper;
    }

    /**
     * Fires when an authenticated request hits a role-restricted route it
     * doesn't have the authority for (e.g. a HOME_OWNER hitting an
     * ADMIN/OPERATIONS-only endpoint). Without this, Spring Security's
     * default AccessDeniedHandler returns a bare 403 with an empty body —
     * this instead returns the same ApiResponse JSON shape as the rest of
     * the API, so callers get an actual message to act on.
     */
    @Bean
    AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.error("You do not have permission to access this resource.")));
        };
    }

    /**
     * Fires when a request to a protected route has no valid authentication
     * at all (missing/expired/malformed JWT) — the 401 counterpart to
     * accessDeniedHandler's 403 above, same reasoning: a real JSON message
     * instead of an empty default body.
     */
    @Bean
    AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.error("Authentication is required to access this resource.")));
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .exceptionHandling(exceptions -> exceptions
                .accessDeniedHandler(accessDeniedHandler())
                .authenticationEntryPoint(authenticationEntryPoint()))
            .authorizeHttpRequests(auth -> auth

                // ── 1. Public: health & docs ───────────────────────────────
                .requestMatchers(
                    "/system/health",
                    "/system/health/details",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/swagger-ui.html"
                ).permitAll()

                // ── 2. Public: payment provider callbacks ──────────────────
                //    (no JWT — Safaricom/Stripe/PayPal servers; IP-secured at gateway)
                //    NOTE: "/payments/stk-callback" and "/payments/c2b-validation" /
                //    "/payments/c2b-confirmation" deliberately have no "mpesa" in
                //    them — Safaricom's sandbox rejects CallBackURL/ValidationURL/
                //    ConfirmationURL values containing that word with
                //    400.002.02 "Invalid CallBackURL".
                .requestMatchers(
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
                ).permitAll()

                // ── 3. Internal, service-to-service calls (API key, not JWT) ──
                //    Authenticated by InternalApiKeyFilter, not JwtAuthenticationFilter.
                //    Must come before the broad "authenticated()" wildcards below,
                //    same reasoning as the role-restricted matchers.
                .requestMatchers("/internal/**")
                    .hasRole("INTERNAL_SERVICE")

                // ── 4. Role-restricted: must come BEFORE the broad wildcards ──
                .requestMatchers("/admin/**")
                    .hasAnyRole("ADMIN", "FINANCE", "OPERATIONS")

                .requestMatchers("/payments/mpesa/c2b/register-urls")
                    .hasAnyRole("ADMIN", "OPERATIONS")

                // ── 5. Authenticated users ─────────────────────────────────
                .requestMatchers(
                    "/system/test-token",
                    "/wallet/**",
                    "/payments/**",
                    "/disbursements/**",
                    "/transactions/**",
                    "/users/**"
                ).authenticated()

                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(internalApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(frontendUrl));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With", "X-API-Key"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}