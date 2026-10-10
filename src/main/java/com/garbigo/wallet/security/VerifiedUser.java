package com.garbigo.wallet.security;

import com.garbigo.wallet.enums.Role;

/**
 * A user as confirmed by the auth service for a presented token.
 */
public record VerifiedUser(String id, String email, Role role, boolean active, boolean verified) {
}