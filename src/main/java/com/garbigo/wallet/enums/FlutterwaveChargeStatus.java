package com.premisave.wallet.enums;

/**
 * Flutterwave v4 charge/transaction status values.
 * Used in charge.completed webhook responses.
 */
public enum FlutterwaveChargeStatus {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    PENDING("pending");

    private final String value;

    FlutterwaveChargeStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static FlutterwaveChargeStatus from(String status) {
        if (status == null) {
            return null;
        }
        for (FlutterwaveChargeStatus s : values()) {
            if (s.value.equalsIgnoreCase(status)) {
                return s;
            }
        }
        return null;
    }
}