package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaypalWebhookRequest {

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("resource")
    private Resource resource;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Resource {
        @JsonProperty("id")
        private String id; // PayPal Order ID, or vault token id for VAULT.PAYMENT-TOKEN.CREATED

        @JsonProperty("status")
        private String status;

        @JsonProperty("purchase_units")
        private List<PurchaseUnit> purchaseUnits;

        // Present on VAULT.PAYMENT-TOKEN.CREATED events
        @JsonProperty("customer")
        private Customer customer;

        @JsonProperty("payment_source")
        private PaymentSource paymentSource;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Customer {
        @JsonProperty("id")
        private String id;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PaymentSource {
        @JsonProperty("paypal")
        private Paypal paypal;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Paypal {
        @JsonProperty("email_address")
        private String emailAddress;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PurchaseUnit {
        @JsonProperty("reference_id")
        private String referenceId;

        @JsonProperty("amount")
        private Amount amount;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Amount {
        @JsonProperty("currency_code")
        private String currencyCode;

        @JsonProperty("value")
        private String value;
    }
}