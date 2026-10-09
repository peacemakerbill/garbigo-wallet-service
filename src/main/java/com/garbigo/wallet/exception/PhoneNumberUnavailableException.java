package com.premisave.wallet.exception;

@SuppressWarnings("serial")
public class PhoneNumberUnavailableException extends RuntimeException {
    public PhoneNumberUnavailableException(String message) {
        super(message);
    }
}