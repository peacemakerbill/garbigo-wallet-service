package com.garbigo.wallet.enums;

/**
 * Must stay in sync with com.garbigo.auth.enums.Role in the auth service.
 * Used to read the role field returned by the auth service (profile / internal endpoints).
 */
public enum Role {
    CLIENT,
    COLLECTOR,
    ADMIN,
    OPERATIONS,
    FINANCE,
    SUPPORT
}