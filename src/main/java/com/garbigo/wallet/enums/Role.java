package com.premisave.wallet.enums;

/**
 * Must stay in sync with com.premisave.auth.enums.Role in the auth service.
 * Used to deserialize the role field returned by the auth service via Feign.
 */
public enum Role {
    CLIENT,
    HOME_OWNER,
    ADMIN,
    OPERATIONS,
    FINANCE,
    SUPPORT
}