package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Body for POST /disbursements/nowpayments/{disbursementId}/verify.
 *
 * verificationCode's origin depends entirely on how 2FA is configured on
 * the NOWPayments account — an app-generated TOTP code, a code read out
 * of an email, or (if 2FA is disabled) not needed at all, in which case
 * this endpoint should never need calling. See
 * DisbursementService.verifyNowPaymentsDisbursement's javadoc.
 */
@Data
public class NowPaymentsVerifyRequest {

    @NotBlank
    private String verificationCode;
}