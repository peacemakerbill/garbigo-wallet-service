package com.premisave.wallet.entity;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransferStatus;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Internal wallet-to-wallet transfer tracking — mirrors Deposit/
 * Disbursement: a dedicated entity instead of the two bare Transaction
 * rows (debit + credit) TransferService used to create with no separate
 * lifecycle record of the transfer itself. Genuinely different shape from
 * both: it touches TWO wallets in one atomic operation, not one, so this
 * carries both a sender and recipient side rather than a single
 * destination/source field the way Disbursement/Deposit do.
 *
 * Always resolves synchronously within one request — unlike Deposit/
 * Disbursement, there's no external provider or webhook to wait on, since
 * both wallets are entirely within wallet-service's own control.
 *
 * status still matters for a real reason though: this record is now
 * created BEFORE the balance mutation (PENDING), rather than only ever
 * being written on success the way the original TransferService did —
 * meaning a failed attempt (recipient not found, wallet frozen,
 * insufficient funds) now leaves a real FAILED record with a reason,
 * instead of vanishing with no trace the moment @Transactional rolls
 * back. This is a genuine behavior change, not just a storage swap —
 * flagged here deliberately since it's a real design decision, not
 * something to bury silently in a "just add an entity" change.
 */
@Data
@Document(collection = "transfers")
public class Transfer {

    @Id
    private String id;

    private String senderId;
    private String senderWalletId;

    /**
     * Sender's email at the time of transfer — sourced from
     * Wallet.accountNumber, which IS the user's email, not a separate
     * lookup. Both wallets are already fetched in
     * TransferService.executeTransfer regardless, so this costs nothing
     * extra to capture. Deliberately NOT the user's full name — that data
     * doesn't exist anywhere in wallet-service's own model at all (it
     * lives in auth-service), and capturing it here would mean a new
     * cross-service call inside the transfer path itself. Confirmed
     * scope: email only, for now.
     */
    private String senderEmail;

    private String recipientId;
    private String recipientWalletId;

    /** Recipient's email at the time of transfer — same sourcing/reasoning as senderEmail above. */
    private String recipientEmail;

    private BigDecimal amount;
    private Currency currency;

    /**
     * What actually left the sender's wallet — amount + commission (see
     * CommissionService, commission.internal-transfer-rate). The
     * recipient still receives exactly `amount`, unaffected — the
     * commission is charged ON TOP of the transfer, not deducted from
     * it, per confirmed design. Equal to amount when the configured rate
     * is zero.
     */
    private BigDecimal totalDebited;

    /**
     * Free-text description of what this transfer is for (e.g. "Rent
     * split", "Repaying a friend") — deliberately a plain String, not a
     * rigid enum, matching how Deposit.provider/Disbursement.provider are
     * also plain strings despite having a known set of common values.
     */
    private String description;

    private TransferStatus status = TransferStatus.PENDING;

    private String reference;

    private String failureReason;

    /**
     * "USER" for an end-user-initiated transfer via POST /wallet/transfer
     * (resolved from their own JWT), or the calling service's name (e.g.
     * "PROPERTY_SERVICE") for one initiated via the internal, API-key-
     * authenticated endpoint. InternalApiKeyFilter carries no JWT/userId
     * at all, so an internal-triggered transfer has to state explicitly
     * who's behind it for audit purposes — there's no authenticated
     * principal to infer it from the way there is for a normal user call.
     */
    private String initiatedBy;

    @CreatedDate
    private LocalDateTime createdAt;
}