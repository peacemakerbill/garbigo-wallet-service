package com.garbigo.wallet.exception;

@SuppressWarnings("serial")
public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException(String message) {
        super(message);
    }
}