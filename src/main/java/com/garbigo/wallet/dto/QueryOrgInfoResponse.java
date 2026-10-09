package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Matches Safaricom's Query Org Info response exactly. Note their own docs
 * are internally inconsistent about what ResponseCode means: the sample
 * successful response shows "4000", but the Error Codes table lists "0" as
 * success and "1 or any other" as rejection. Because of that ambiguity,
 * `success` here is derived from organizationName being present rather than
 * from ResponseCode alone — see MpesaService.queryOrgInfo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryOrgInfoResponse {
    private boolean success;
    private String conversationId;
    private String responseCode;
    private String responseMessage;
    private String detailedMessage;
    private String organizationShortCode;
    private String organizationName;
    private String chargeProfileId;
}