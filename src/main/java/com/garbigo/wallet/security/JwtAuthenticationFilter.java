package com.garbigo.wallet.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates requests carrying an auth-service JWT.
 *
 * 1. Verifies the signature and expiry locally (JwtService, shared JWT_SECRET).
 * 2. Confirms the token with the auth service (AuthServiceTokenVerifier) to pick up
 *    the user's role and to honour logout, since the token itself has no role claim.
 *
 * Controllers keep working as before: auth.getName() is the user's email and
 * request attribute "userId" is the userId claim.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AuthServiceTokenVerifier tokenVerifier;

    public JwtAuthenticationFilter(JwtService jwtService, AuthServiceTokenVerifier tokenVerifier) {
        this.jwtService = jwtService;
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String jwt = authHeader.substring(7);

        try {
            if (SecurityContextHolder.getContext().getAuthentication() == null && jwtService.isTokenValid(jwt)) {
                String email = jwtService.extractUsername(jwt);
                String jti = jwtService.extractJti(jwt);
                String userIdClaim = jwtService.extractUserId(jwt);
                long expiry = jwtService.extractExpiration(jwt).getTime();

                Optional<VerifiedUser> verified = tokenVerifier.verify(jwt, jti, expiry);

                if (email != null && verified.isPresent() && verified.get().active()) {
                    VerifiedUser user = verified.get();

                    List<SimpleGrantedAuthority> authorities = user.role() != null
                            ? List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))
                            : List.of();

                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(email, null, authorities);
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // Stash userId so controllers do not need to parse the JWT again
                    String userId = userIdClaim != null ? userIdClaim : user.id();
                    if (userId != null) {
                        request.setAttribute("userId", userId);
                    }

                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            // Invalid/expired token: continue unauthenticated; Spring Security
            // rejects it at the authorization layer
            logger.warn("JWT processing failed: " + e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}