package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from initiating a Flutterwave account link.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LinkFlutterwaveResponse {

    /**
     * Link token issued by Flutterwave for this account linking session.
     * Frontend uses this to redirect to Flutterwave's authorization flow.
     */
    private String linkToken;

    /**
     * Flutterwave authorization URL the frontend should redirect to.
     * User completes authorization there, then is redirected back to redirectUrl.
     */
    private String authorizationUrl;

    /**
     * Pending setup token id stored in wallet during linking.
     * Used on callback to verify this link request belongs to this wallet.
     */
    private String pendingSetupTokenId;
}