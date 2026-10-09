package com.garbigo.wallet.exception;

@SuppressWarnings("serial")
public class PhoneNumberUnavailableException extends RuntimeException {
    public PhoneNumberUnavailableException(String message) {
        super(message);
    }
}