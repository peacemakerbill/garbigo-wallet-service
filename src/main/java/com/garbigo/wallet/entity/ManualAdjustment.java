package com.premisave.wallet.entity;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.ManualAdjustmentType;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Manual admin balance adjustment tracking — mirrors Deposit/
 * Disbursement/Transfer/Payment: a dedicated entity instead of whatever
 * AdminWalletService.creditWallet/debitWallet currently create (likely a
 * bare Transaction row, same gap every other entity had before its own
 * migration).
 *
 * Distinct from every other entity in one important way: this represents
 * a human unilaterally overriding a balance, not a normal transactional
 * event — so it carries balanceBefore/balanceAfter (a direct, unambiguous
 * audit trail of exactly what changed) and performedBy (WHICH admin did
 * this, not just "USER" vs a service name — a manual override is
 * precisely the kind of action that most needs individual accountability).
 */
@Data
@Document(collection = "manual_adjustments")
public class ManualAdjustment {

    @Id
    private String id;

    private String userId;
    private String walletId;

    /**
     * The wallet's account number (= the user's email) at the time of
     * adjustment — sourced from Wallet.accountNumber, which is already
     * fetched in AdminWalletService.creditWallet/debitWallet for the
     * balance check, so this costs nothing extra to capture. Same
     * reasoning as Transfer.senderEmail/recipientEmail and Payment.email
     * — lets an admin read who a walletId actually belongs to directly
     * off the adjustment record, without a separate wallet lookup.
     */
    private String accountNumber;

    private ManualAdjustmentType type;
    private BigDecimal amount;
    private Currency currency;

    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;

    private String reason;

    /** Auto-generated, never client-supplied — see AdminWalletService for why. */
    @Indexed(unique = true)
    private String reference;

    /** Which admin performed this adjustment — resolved from the caller's own JWT, never taken from the request body. */
    private String performedBy;

    @CreatedDate
    private LocalDateTime createdAt;
}