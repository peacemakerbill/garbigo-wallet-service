package com.premisave.wallet.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds UserDetails from the email passed by JwtAuthenticationFilter.
 * The role is injected separately by the filter after extracting it from the JWT.
 * No Feign call needed — the JWT is already verified against the shared secret.
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    /**
     * Called by JwtAuthenticationFilter with just the email (JWT subject).
     * Returns a minimal UserDetails — authorities are set by the filter
     * after extracting the role claim directly from the token.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        if (email == null || email.isBlank()) {
            throw new UsernameNotFoundException("Email must not be blank");
        }
        // Return a shell — the filter will override authorities from JWT claims
        return User.builder()
                .username(email)
                .password("")
                .authorities(List.of())
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .disabled(false)
                .build();
    }
}