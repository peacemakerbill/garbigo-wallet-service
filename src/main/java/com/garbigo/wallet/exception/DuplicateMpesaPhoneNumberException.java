package com.premisave.wallet.exception;

/**
 * Thrown when a user tries to set their wallet's mpesaPhoneNumber to a
 * number that's already registered to a different wallet. M-Pesa numbers
 * must be unique because they're now the account reference used to route
 * C2B Pay Bill deposits to the correct wallet (see
 * MpesaC2BService.validateAccount/processConfirmation) — a shared number
 * would misdirect deposits between two accounts.
 */
@SuppressWarnings("serial")
public class DuplicateMpesaPhoneNumberException extends RuntimeException {
    public DuplicateMpesaPhoneNumberException(String message) {
        super(message);
    }
}