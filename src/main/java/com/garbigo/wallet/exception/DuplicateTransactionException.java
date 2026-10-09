package com.premisave.wallet.exception;

@SuppressWarnings("serial")
public class DuplicateTransactionException extends RuntimeException {
    public DuplicateTransactionException(String message) {
        super(message);
    }
}