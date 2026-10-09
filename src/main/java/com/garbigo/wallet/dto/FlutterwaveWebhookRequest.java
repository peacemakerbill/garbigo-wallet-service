package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import lombok.Data;

/**
 * Flutterwave sends two structurally different payloads under the same
 * webhook endpoint depending on event type: "charge.completed" (deposits)
 * and "transfer.disburse" (disbursements). Rather than modeling both shapes
 * strictly and keeping them in sync with Flutterwave's docs, eventData is
 * left as a raw JsonNode — PaymentCallbackController reads the specific
 * fields it needs per event type, same approach already used for PayPal's
 * PAYMENT.PAYOUTS-ITEM.* backstop parsing.
 *
 * IMPORTANT: Flutterwave's actual v4 payload names this field "type", NOT
 * "event" — e.g. {"webhook_id":"...","timestamp":...,"type":"charge.completed",
 * "data":{...}}. Mapping @JsonProperty to "event" here previously left
 * this.event always null, silently no-opping every branch in
 * PaymentCallbackController.flutterwaveWebhook(). See
 * https://developer.flutterwave.com/docs/webhooks — "Structure of a
 * Webhook Payload". The same shape is also used for the optional
 * per-transfer callback_url (see FlutterwaveService.initiateTransfer /
 * FlutterwaveConfig.Transfer.callbackUrl), so both the dashboard-registered
 * account-wide webhook and a per-transfer callback can point at this same
 * controller endpoint and DTO.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlutterwaveWebhookRequest {

    @JsonProperty("type")
    private String event; // "charge.completed" | "transfer.disburse"

    @JsonProperty("data")
    private JsonNode eventData;
}