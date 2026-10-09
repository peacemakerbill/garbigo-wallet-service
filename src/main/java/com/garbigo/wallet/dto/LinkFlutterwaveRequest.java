package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to link/connect a Flutterwave account for easy one-click checkout.
 * Initiates the Flutterwave link flow (similar to PayPal vault setup).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LinkFlutterwaveRequest {

    /**
     * Optional phone number for the mobile money account.
     * Can be pre-filled if user provides it; Flutterwave link flow may override.
     */
    private String phoneNumber;

    /**
     * Optional mobile network (e.g., "MTN", "AIRTEL", "VODAFONE").
     * Hint for which network the account is on; Flutterwave flow may adjust.
     */
    private String network;

    /**
     * Redirect URL for the user after Flutterwave account linking completes.
     * Falls back to configuration default if not provided.
     */
    private String redirectUrl;
}