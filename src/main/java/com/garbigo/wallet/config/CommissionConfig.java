package com.premisave.wallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Commission rates the company takes as a cut of user money movements —
 * genuinely separate from Payment (ad subscriptions etc.), where the
 * whole amount IS company revenue with no rate involved at all.
 *
 * TWO deliberately separate rates, not one — internal wallet-to-wallet
 * transfers (money never leaves Premisave) and gateway-bound
 * disbursements (M-Pesa B2C, PayPal, Stripe, Flutterwave, NOWPayments,
 * B2Pochi — money leaves the platform to an external provider) are
 * expected to carry meaningfully different rates, gateway higher than
 * internal, matching real payment-processing economics (an external
 * gateway withdrawal costs the platform real processing fees on top;
 * an internal transfer costs nothing external at all).
 *
 * Both are decimal fractions, not percentages — 0.10 means 10%, not 10.
 * amount.multiply(rate) gives the commission amount directly.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "commission")
public class CommissionConfig {

    /** Rate applied to internal wallet-to-wallet transfers (POST /wallet/transfer, POST /internal/transfer). Expected to be the lower of the two. */
    private BigDecimal internalTransferRate;

    /** Rate applied to gateway-bound disbursements — M-Pesa B2C/B2Pochi, PayPal, Stripe, Flutterwave, NOWPayments. Expected to be the higher of the two. */
    private BigDecimal gatewayRate;
}