package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Matches Safaricom's actual STK Push ("Lipa Na M-Pesa Online") callback payload,
 * which is nested — NOT a flat object. Example (successful payment):
 *
 * {
 *   "Body": {
 *     "stkCallback": {
 *       "MerchantRequestID": "29115-34620561-1",
 *       "CheckoutRequestID": "ws_CO_191220191020363925",
 *       "ResultCode": 0,
 *       "ResultDesc": "The service request is processed successfully.",
 *       "CallbackMetadata": {
 *         "Item": [
 *           {"Name": "Amount", "Value": 1.00},
 *           {"Name": "MpesaReceiptNumber", "Value": "NLJ7RT61SV"},
 *           {"Name": "TransactionDate", "Value": 20191219102151},
 *           {"Name": "PhoneNumber", "Value": 254708374149}
 *         ]
 *       }
 *     }
 *   }
 * }
 *
 * On failure/cancellation (ResultCode != 0), CallbackMetadata is absent entirely.
 */
@Data
public class MpesaStkCallbackRequest {

    @JsonProperty("Body")
    private Body body;

    @Data
    public static class Body {
        @JsonProperty("stkCallback")
        private StkCallback stkCallback;
    }

    @Data
    public static class StkCallback {
        @JsonProperty("MerchantRequestID")
        private String merchantRequestID;

        @JsonProperty("CheckoutRequestID")
        private String checkoutRequestID;

        @JsonProperty("ResultCode")
        private int resultCode;

        @JsonProperty("ResultDesc")
        private String resultDesc;

        @JsonProperty("CallbackMetadata")
        private CallbackMetadata callbackMetadata;
    }

    @Data
    public static class CallbackMetadata {
        @JsonProperty("Item")
        private List<CallbackItem> item;
    }

    @Data
    public static class CallbackItem {
        @JsonProperty("Name")
        private String name;

        @JsonProperty("Value")
        private Object value;
    }
}