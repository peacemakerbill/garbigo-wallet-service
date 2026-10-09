package com.garbigo.wallet.exception;

@SuppressWarnings("serial")
public class WalletFrozenException extends RuntimeException {
    public WalletFrozenException(String message) {
        super(message);
    }
}