package com.premisave.wallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mpesa.daraja")
public class MpesaConfig {
    private String consumerKey;
    private String consumerSecret;
    private String shortcode;
    private String passkey;
    private String callbackUrl;
    private String environment; // "sandbox" or "production"

    /**
     * Path to Safaricom's public certificate (.cer) used to RSA-encrypt the
     * initiator password into a SecurityCredential for B2C/B2B/TransactionStatus.
     * Sandbox and production use DIFFERENT certificates — download both from
     * https://developer.safaricom.co.ke and point this at the correct one
     * per environment (e.g. via MPESA_CERT_PATH env var per deploy).
     */
    private String certificatePath;

    private final C2b c2b = new C2b();
    private final B2c b2c = new B2c();
    private final B2b b2b = new B2b();
    private final TransactionStatus transactionStatus = new TransactionStatus();
    private final AccountBalance accountBalance = new AccountBalance();
    private final Reversal reversal = new Reversal();
    private final B2Pochi b2Pochi = new B2Pochi();
    private final PullTransactions pullTransactions = new PullTransactions();

    public String baseUrl() {
        return "sandbox".equalsIgnoreCase(environment)
                ? "https://sandbox.safaricom.co.ke"
                : "https://api.safaricom.co.ke";
    }

    @Data
    public static class C2b {
        private String shortcode;
        private String responseType;
        private String validationUrl;
        private String confirmationUrl;
    }

    /**
     * B2C (Business to Customer — disbursements/payouts to a phone number).
     */
    @Data
    public static class B2c {
        private String initiatorName;
        private String initiatorPassword;
        private String shortcode;
        private String commandId; // BusinessPayment | SalaryPayment | PromotionPayment
        private String queueTimeoutUrl;
        private String resultUrl;

        /** Per-transaction limits — confirm actual tier limits with Safaricom for your shortcode. */
        private java.math.BigDecimal minAmount = new java.math.BigDecimal("10");
        private java.math.BigDecimal maxAmount = new java.math.BigDecimal("150000");
    }

    /**
     * B2B (Business to Business — payments to another paybill/till shortcode).
     * NOTE: B2B is a permissioned API — must be explicitly enabled for your
     * shortcode by Safaricom before this will work in production.
     *
     * commandId must be one of Safaricom's officially supported values:
     * BusinessPayBill, BusinessBuyGoods, DisburseFundsToBusiness,
     * BusinessToBusinessTransfer, BusinessTransferFromMMFToUtility,
     * BusinessTransferFromUtilityToMMF, MerchantToMerchantTransfer,
     * MerchantTransferFromMerchantToWorking, MerchantServicesMMFAccountTransfer,
     * AgencyFloatAdvance.
     */
    @Data
    public static class B2b {
        private String initiatorName;
        private String initiatorPassword;
        private String shortcode;
        private String commandId; // BusinessPayBill | BusinessBuyGoods | MerchantToMerchantTransfer | ...
        private String senderIdentifierType = "4";
        private String receiverIdentifierType = "4";
        private String queueTimeoutUrl;
        private String resultUrl;

        private java.math.BigDecimal minAmount = new java.math.BigDecimal("10");
        private java.math.BigDecimal maxAmount = new java.math.BigDecimal("150000");
    }

    /**
     * Transaction Status — secondary reconciliation mechanism for when a
     * B2C/B2B/C2B/Reversal ResultURL callback never arrives. Query by either
     * TransactionID (M-Pesa receipt) or OriginalConversationID.
     * See https://developer.safaricom.co.ke/apis/TransactionStatus
     */
    @Data
    public static class TransactionStatus {
        private String initiatorName;
        private String initiatorPassword;
        /** Own shortcode being queried against — PartyA. */
        private String partyA;
        /** 1=MSISDN, 2=Till, 4=Shortcode. Shortcodes use 4. */
        private String identifierType = "4";
        private String queueTimeoutUrl;
        private String resultUrl;
    }

    /**
     * Account Balance — real-time balance inquiry across the Working (MMF),
     * Utility, and Charges Paid accounts for our own shortcode.
     * See https://developer.safaricom.co.ke/apis/AccountBalance
     */
    @Data
    public static class AccountBalance {
        private String initiatorName;
        private String initiatorPassword;
        private String partyA;
        private String identifierType = "4";
        private String queueTimeoutUrl;
        private String resultUrl;
    }

    /**
     * Reversal — reverses a completed C2B transaction, refunding the
     * customer and debiting our shortcode. B2C payouts cannot be reversed
     * via this API (Safaricom portal only).
     * See https://developer.safaricom.co.ke/apis/Reversal
     */
    @Data
    public static class Reversal {
        private String initiatorName;
        private String initiatorPassword;
        /** Our own shortcode — the ReceiverParty debited by the reversal. */
        private String receiverParty;
        /** Must be "11" per Safaricom's Reversal spec. */
        private String receiverIdentifierType = "11";
        private String queueTimeoutUrl;
        private String resultUrl;
    }

    /**
     * B2Pochi (Business to Pochi la Biashara) — a B2C variant that pays
     * directly into a customer's Pochi business wallet instead of their
     * main M-Pesa balance. Requires a B2C shortcode / "one account" capable
     * of both receiving and disbursing.
     * See https://developer.safaricom.co.ke/apis/BusinessToPochi
     */
    @Data
    public static class B2Pochi {
        private String initiatorName;
        private String initiatorPassword;
        /** B2C shortcode money is sent FROM. */
        private String partyA;
        private String queueTimeoutUrl;
        private String resultUrl;

        private java.math.BigDecimal minAmount = new java.math.BigDecimal("10");
        private java.math.BigDecimal maxAmount = new java.math.BigDecimal("250000");
    }

    /**
     * Pull Transactions — reconciliation API that lets us query all C2B
     * transactions performed under our shortcode within the last 48 hours,
     * to recover any that failed to reach our C2B confirmation callback.
     * Unlike every other "operational" M-Pesa API in this config, this one
     * needs NO initiator name/password/SecurityCredential — auth is via the
     * normal OAuth bearer token only, same as STK Push and C2B Register URL.
     * See https://developer.safaricom.co.ke/apis/PullTransaction
     */
    @Data
    public static class PullTransactions {
        /** Defaults to the root shortcode if unset. */
        private String shortcode;
        /**
         * The Safaricom MSISDN associated with the organization account,
         * found on the M-PESA portal under the shortcode's KYC details.
         * Required by Safaricom's Register Pull request.
         */
        private String nominatedNumber;
        /** Our own webhook — required by Register Pull; exact push payload is undocumented by Safaricom. */
        private String callbackUrl;
        /** Default lookback window (days) when a query doesn't specify start/end dates. Max 2 (48h retention). */
        private int pullDays = 1;
        private int offsetValue = 0;
    }

    /**
     * "B2B Hakikisha" (Query Org Info) — returns an organization's registered
     * name and tariff/charge profile for a given shortcode/till, so a B2B
     * payment's recipient can be confirmed before money moves. Synchronous
     * (no ResultURL/QueueTimeoutURL — response comes back immediately) and
     * needs no initiator/SecurityCredential, just the OAuth bearer token.
     *
     * Unlike the rest of this config, there's no per-environment sub-block:
     * the endpoint isn't published under mpesa.daraja.* — it's derived from
     * baseUrl() below, same as every other Safaricom-side operation
     * (STK push, B2C, B2B, Account Balance, etc.).
     * See https://developer.safaricom.co.ke/apis/QueryOrgInfo
     */
    public String queryOrgInfoUrl() {
        return baseUrl() + "/sfcverify/v1/query/info";
    }

    // Registration of PullTransactions is a one-time step per shortcode —
    // see MpesaService.registerPullTransactions() / PullTransactionService.
}