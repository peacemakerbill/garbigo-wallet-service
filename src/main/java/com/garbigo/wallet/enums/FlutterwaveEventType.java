package com.premisave.wallet.enums;

/**
 * Flutterwave v4 webhook event types.
 * These replace hardcoded string comparisons in PaymentCallbackController.
 */
public enum FlutterwaveEventType {
    /** Charge completed event (deposits). */
    CHARGE_COMPLETED("charge.completed"),
    
    /** Transfer disbursement event (payouts). */
    TRANSFER_DISBURSE("transfer.disburse");

    private final String value;

    FlutterwaveEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Resolve event type from webhook event string.
     *
     * @param event the event type string from Flutterwave webhook
     * @return the corresponding FlutterwaveEventType, or null if not recognized
     */
    public static FlutterwaveEventType from(String event) {
        if (event == null) {
            return null;
        }
        for (FlutterwaveEventType type : values()) {
            if (type.value.equals(event)) {
                return type;
            }
        }
        return null;
    }
}