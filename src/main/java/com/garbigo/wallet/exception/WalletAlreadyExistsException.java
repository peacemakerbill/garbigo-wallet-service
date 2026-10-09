package com.premisave.wallet.exception;

/**
 * Thrown when attempting to create a wallet for an account (email) or
 * userId that already has one. Callers should GET /wallet instead of
 * re-POSTing /wallet/create.
 */
@SuppressWarnings("serial")
public class WalletAlreadyExistsException extends RuntimeException {
    public WalletAlreadyExistsException(String message) {
        super(message);
    }
}