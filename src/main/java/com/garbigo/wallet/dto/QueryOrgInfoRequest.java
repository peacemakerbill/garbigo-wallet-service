package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * "B2B Hakikisha" (Query Org Info) — looks up the registered name and
 * applicable tariff for an M-Pesa organization account, so a B2B payment's
 * recipient can be confirmed before money moves.
 * See https://developer.safaricom.co.ke/apis/QueryOrgInfo
 */
@Data
public class QueryOrgInfoRequest {

    /**
     * "2" = Lipa na M-PESA till number / agent till number.
     * "4" = PayBill, B2C account, or any other shortcode not covered by type 2.
     */
    @NotBlank
    private String identifierType;

    /** The shortcode/till number registered under the organization being queried. */
    @NotBlank
    private String identifier;
}