package com.premisave.wallet.exception;

/**
 * Thrown when attempting to unfreeze a wallet that isn't currently
 * frozen — prevents a silent no-op from being reported back as a fresh
 * success.
 */
@SuppressWarnings("serial")
public class WalletNotFrozenException extends RuntimeException {
    public WalletNotFrozenException(String message) {
        super(message);
    }
}