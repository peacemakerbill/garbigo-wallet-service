package com.premisave.wallet.exception;

/**
 * Thrown when attempting to freeze a wallet that is already frozen —
 * prevents a silent no-op from being reported back as a fresh success.
 */
@SuppressWarnings("serial")
public class WalletAlreadyFrozenException extends RuntimeException {
    public WalletAlreadyFrozenException(String message) {
        super(message);
    }
}