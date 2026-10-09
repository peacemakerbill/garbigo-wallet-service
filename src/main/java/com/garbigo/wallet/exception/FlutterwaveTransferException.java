package com.premisave.wallet.exception;

/**
 * Thrown when a Flutterwave Transfer (disbursement) request blows up before
 * a clean response could even be parsed (network error, malformed body) —
 * distinct from Flutterwave answering normally but rejecting the transfer
 * (that comes back as a plain FlutterwaveService.TransferResult(false, ...),
 * not this exception). Mirrors the role PaypalCaptureException plays for
 * PayPal captures.
 */
@SuppressWarnings("serial")
public class FlutterwaveTransferException extends RuntimeException {

    private final String reference;
    private final String errorCode;

    public FlutterwaveTransferException(String reference, String errorCode, String message) {
        super(message);
        this.reference = reference;
        this.errorCode = errorCode;
    }

    public String getReference() {
        return reference;
    }

    public String getErrorCode() {
        return errorCode;
    }
}