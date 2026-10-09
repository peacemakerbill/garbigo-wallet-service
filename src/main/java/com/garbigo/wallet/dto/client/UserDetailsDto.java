package com.premisave.wallet.dto.client;

import com.premisave.wallet.enums.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * DTO received from Auth Service via Feign.
 * Mirrors the Auth Service's User entity UserDetails implementation exactly.
 *
 * firstName/middleName/lastName/fullName added — the auth-service side
 * (UserDetailsInternalResponse) computes fullName itself the same way
 * UserDto.getFullName() already does there, and sends it as a plain JSON
 * field; deserialized directly here as a record component rather than
 * recomputed on this side, since the auth service already did that work.
 * Any of these four may be null if the corresponding name field was
 * never set on the user's account, or if the lookup itself failed —
 * callers must handle that (EmailService already treats a null/blank
 * name as "omit this row", never as an error).
 */
public record UserDetailsDto(
        String id,
        String email,
        Role role,
        boolean active,
        boolean verified,
        String firstName,
        String middleName,
        String lastName,
        String fullName
) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        // Password is never returned from the auth service to other services
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }

    // Mirrors User entity: all three return true unconditionally
    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    // Mirrors User entity: active AND verified must both be true
    @Override
    public boolean isEnabled() {
        return active && verified;
    }
}