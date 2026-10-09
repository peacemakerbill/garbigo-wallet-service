package com.premisave.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Business-to-Pochi payment: disburses from our B2C shortcode straight into
 * a customer's "Pochi la Biashara" business wallet (CommandID BusinessPayToPochi).
 * See https://developer.safaricom.co.ke/apis/BusinessToPochi
 */
@Data
public class B2PochiRequest {

    @NotNull
    @DecimalMin("10.00")
    private BigDecimal amount;

    /**
     * IGNORED — the recipient phone number is always resolved from the
     * caller's own verified profile (see
     * DisbursementService.resolveVerifiedPhoneNumber), same as B2C. Omit
     * this field entirely; kept only so older clients that still send it
     * don't fail deserialization.
     */
    private String phoneNumber;

    private String remarks;

    private String occasion;

    /** Optional idempotency key — generated if not provided. */
    private String reference;

    /**
     * KES or USD — which currency amount above is denominated in.
     * Defaults to KES if omitted, same reasoning as
     * DisbursementRequest.nowPaymentsPriceCurrency's default: every
     * existing caller of this endpoint has always assumed amount is KES
     * (this is what M-Pesa itself actually pays out in), so flipping the
     * default here would silently reinterpret existing requests rather
     * than just adding a new option. Set to "USD" explicitly to specify
     * how much to withdraw in dollars instead — it's converted to the
     * real KES amount before being sent to Safaricom's B2Pochi API,
     * which only ever understands KES.
     */
    private String currency;
}