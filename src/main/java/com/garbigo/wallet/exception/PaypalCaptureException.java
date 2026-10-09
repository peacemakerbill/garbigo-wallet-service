package com.premisave.wallet.exception;

/**
 * Thrown when PayPal's order capture call fails or returns a non-success
 * status. Distinguishes "already captured" (a legitimate idempotency
 * signal — e.g. a race between the webhook and the frontend confirm call)
 * from a genuine failure, so callers can handle each case correctly.
 */
@SuppressWarnings("serial")
public class PaypalCaptureException extends RuntimeException {

    private final String orderId;
    private final String issue;

    public PaypalCaptureException(String orderId, String issue, String rawResponse) {
        super("PayPal capture failed for orderId=" + orderId + " issue=" + issue + " raw=" + rawResponse);
        this.orderId = orderId;
        this.issue = issue;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getIssue() {
        return issue;
    }

    public boolean isAlreadyCaptured() {
        return "ORDER_ALREADY_CAPTURED".equalsIgnoreCase(issue);
    }
}