package com.premisave.wallet.dto;

import com.premisave.wallet.enums.Currency;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletResponse {
    private String id;
    private String accountNumber;
    private String userId;
    private BigDecimal balance;
    private Currency currency;
    private boolean isFrozen;

    /** Null if never set. */
    private String paypalEmail;

    /** Null if never set. Normalized to 254XXXXXXXXX. */
    private String mpesaPhoneNumber;

    /** Phone number the user's Pochi la Biashara account is registered under, used for B2Pochi withdrawals. Null if never set — falls back to mpesaPhoneNumber. Normalized to 254XXXXXXXXX. */
    private String pochiPhoneNumber;

    /** True once a card is saved for one-click deposit reloads. */
    private boolean hasSavedCard;

    /** Display-only, null if hasSavedCard is false. Never expose raw Stripe IDs here. */
    private String cardBrand;
    private String cardLast4;

    /** True once a PayPal account is saved (vaulted) for faster repeat deposits. */
    private boolean hasPaypalConnected;

    /** Display-only, null if hasPaypalConnected is false. Never expose the raw vault_id/customer_id here. */
    private String paypalConnectedEmail;
}