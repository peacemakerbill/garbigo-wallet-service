package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Matches Safaricom's real B2C / B2B / B2Pochi / Account Balance /
 * Transaction Status / Reversal "Result" callback envelope (ResultURL).
 * Distinct from the STK callback shape (Body.stkCallback) — this one is
 * a top-level "Result" object.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) is applied on every nested
 * class here (not just the root) — Jackson's ignoreUnknown setting is
 * per-class, so a field like "ReferenceData" nested inside Result would
 * still fail deserialization if only the outer class ignored unknowns.
 *
 * This was previously missing, which caused every B2C/B2B/B2Pochi/Balance/
 * TransactionStatus/Reversal ResultURL callback to be rejected with
 * HttpMessageNotReadableException (400) before PaymentCallbackController's
 * handler methods ever ran — Safaricom's actual payload includes a
 * ReferenceData object this class didn't model. Safaricom retries
 * indefinitely on a non-200, which is what caused the repeated callback
 * attempts visible in the zrok tunnel logs.
 *
 * resultCode is a String, NOT an int/Integer — confirmed both from a real
 * captured sandbox callback ("ResultCode":"TP40153", an Account Balance
 * permission-denied error) AND directly from Safaricom's own Account
 * Balance API documentation's parameter table: "Result Code ... Max
 * length is 10 ... Type: String ... Sample Values: 0". A previous version
 * of this field was typed as int, which crashed with
 * HttpMessageNotReadableException the first time Safaricom ever sent an
 * alphanumeric code rather than "0" — every prior callback in testing
 * happened to succeed with a plain "0", which is why this went
 * undetected until now. resultType, by contrast, genuinely IS documented
 * as Integer ("0: completed, 1: waiting for further messages") and is
 * correctly typed as-is.
 *
 * Success example:
 * {
 *   "Result": {
 *     "ResultType": 0,
 *     "ResultCode": "0",
 *     "ResultDesc": "The service request is processed successfully.",
 *     "OriginatorConversationID": "...",
 *     "ConversationID": "AG_20191219_...",
 *     "TransactionID": "NLJ7RT61SV",
 *     "ResultParameters": {
 *       "ResultParameter": [
 *         {"Key": "TransactionAmount", "Value": 100},
 *         {"Key": "TransactionReceipt", "Value": "NLJ7RT61SV"},
 *         {"Key": "ReceiverPartyPublicName", "Value": "254712345678 - John Doe"}
 *       ]
 *     },
 *     "ReferenceData": {
 *       "ReferenceItem": {"Key": "QueueTimeoutURL", "Value": "..."}
 *     }
 *   }
 * }
 *
 * On failure, ResultParameters/ReferenceData are often absent — only
 * ResultCode/ResultDesc matter.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MpesaResultCallbackRequest {

    @JsonProperty("Result")
    private Result result;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        @JsonProperty("ResultType")
        private int resultType;

        /**
         * Deliberately String, not int — see class javadoc. A whole-number
         * success code ("0") and an alphanumeric error code ("TP40153")
         * both need to fit here; only String can hold both.
         */
        @JsonProperty("ResultCode")
        private String resultCode;

        @JsonProperty("ResultDesc")
        private String resultDesc;

        @JsonProperty("OriginatorConversationID")
        private String originatorConversationID;

        @JsonProperty("ConversationID")
        private String conversationID;

        @JsonProperty("TransactionID")
        private String transactionID;

        @JsonProperty("ResultParameters")
        private ResultParameters resultParameters;

        // ReferenceData is intentionally NOT mapped — nothing in this
        // service currently needs it (it's typically just an echo of the
        // QueueTimeoutURL that was configured for the request). It's still
        // sent by Safaricom on most callbacks, which is why ignoreUnknown
        // above matters: this field must be tolerated, not necessarily read.
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResultParameters {
        /**
         * @JsonFormat(with = ACCEPT_SINGLE_VALUE_AS_ARRAY) confirmed
         * necessary from a real captured B2B failure callback
         * ("The balance is insufficient for the transaction."), where
         * Safaricom sent ResultParameter as a single bare JSON OBJECT
         * rather than a one-element array — every other captured
         * callback tonight with result parameters had multiple entries,
         * always wrapped in [...]. Without this, Jackson rejected the
         * whole request with HttpMessageNotReadableException (400)
         * before PaymentCallbackController's handler ever ran, meaning
         * this exact real failure ("balance insufficient") was NEVER
         * actually recorded — the disbursement was left stuck in
         * PENDING with no failureReason, since the code that would have
         * marked it FAILED never got to execute at all.
         *
         * Scoped to just this one field via @JsonFormat rather than a
         * global Jackson config change (e.g.
         * spring.jackson.deserialization.accept-single-value-as-array),
         * which would have silently changed array-tolerance behavior
         * for every other JSON deserialization in the whole app.
         */
        @JsonProperty("ResultParameter")
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        private List<ResultParameter> resultParameter;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResultParameter {
        @JsonProperty("Key")
        private String key;

        @JsonProperty("Value")
        private Object value;
    }
}