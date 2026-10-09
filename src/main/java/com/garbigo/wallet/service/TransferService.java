package com.premisave.wallet.service;

import com.premisave.wallet.dto.InternalTransferRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.dto.TransferRecordResponse;
import com.premisave.wallet.dto.TransferRequest;
import com.premisave.wallet.entity.Transfer;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransferStatus;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.TransferRepository;
import com.premisave.wallet.repository.WalletRepository;
import com.premisave.wallet.util.DateRangeCriteriaUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wallet-to-wallet transfer logic — mirrors DisbursementService's real
 * behavior (not just its status enum): validation failures (recipient not
 * found, self-transfer, frozen, insufficient funds) still throw the same
 * typed exceptions as before, with NO Transfer record created. A
 * Transfer completes entirely within one request (no external provider,
 * no webhook to wait on), so the record is created once, with its final
 * status, right when the balance mutation actually happens.
 *
 * COMMISSION: charged ON TOP of the stated amount, confirmed explicitly
 * — the sender pays amount + commission; the recipient receives exactly
 * `amount`, completely unaffected. See CommissionService's javadoc for
 * the full reasoning. Nothing is credited to any real company wallet —
 * the commission is only ever recorded in CompanyLedgerEntry for
 * reporting, confirmed explicitly.
 *
 * Two public entry points, one shared implementation:
 *  - transfer() — user-initiated, sender resolved from their own JWT.
 *  - transferInternal() — another Premisave service calling via
 *    POST /internal/transfer. Requires an explicit senderUserId in the
 *    request body, since InternalApiKeyFilter authenticates the CALLING
 *    SERVICE, not an end user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final MongoTemplate mongoTemplate;
    private final WalletService walletService;
    private final IdempotencyService idempotencyService;
    private final TransferTransactionRecorder transferTransactionRecorder;
    private final CommissionService commissionService;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;

    @Transactional
    public PaymentResponse transfer(String senderUserId, TransferRequest request) {
        return executeTransfer(senderUserId, request.getRecipientAccountNumber(), request.getAmount(),
                request.getDescription(), request.getReference(), "USER");
    }

    @Transactional
    public PaymentResponse transferInternal(InternalTransferRequest request) {
        return executeTransfer(request.getSenderUserId(), request.getRecipientAccountNumber(), request.getAmount(),
                request.getDescription(), request.getReference(), request.getInitiatedBy());
    }

    private PaymentResponse executeTransfer(String senderUserId, String recipientAccountNumber, BigDecimal amount,
                                             String description, String requestedReference, String initiatedBy) {
        // Resolved BEFORE the idempotency check -- see PaymentService's
        // identical fix for why this ordering matters.
        String reference = requestedReference != null ? requestedReference : UUID.randomUUID().toString();
        idempotencyService.checkIdempotency(reference);

        Wallet sender = walletRepository.findByUserId(senderUserId)
                .orElseThrow(() -> new WalletNotFoundException("Sender wallet not found for userId: " + senderUserId));

        Wallet recipient = walletRepository.findByAccountNumber(recipientAccountNumber)
                .orElseThrow(() -> new WalletNotFoundException(
                        "Recipient wallet not found for account: " + recipientAccountNumber));

        if (sender.getId().equals(recipient.getId())) {
            throw new IllegalArgumentException("Cannot transfer funds to your own wallet");
        }

        BigDecimal commission = commissionService.calculateInternalTransferCommission(amount);
        BigDecimal totalDebit = amount.add(commission);

        // Validated against the TOTAL the sender actually needs — amount
        // + commission, not just the nominal transfer amount — since the
        // commission is charged on top, not deducted from the transfer.
        walletService.validateWalletForTransaction(senderUserId, totalDebit);

        if (recipient.isFrozen()) {
            throw new WalletFrozenException("Recipient wallet is currently frozen and cannot receive funds");
        }

        // Perform transfer — sender pays amount PLUS commission;
        // recipient receives exactly the stated amount, unaffected.
        sender.setBalance(sender.getBalance().subtract(totalDebit));
        recipient.setBalance(recipient.getBalance().add(amount));

        walletRepository.save(sender);
        walletRepository.save(recipient);

        Transfer transfer = new Transfer();
        transfer.setSenderId(senderUserId);
        transfer.setSenderWalletId(sender.getId());
        transfer.setSenderEmail(sender.getAccountNumber());
        transfer.setRecipientId(recipient.getUserId());
        transfer.setRecipientWalletId(recipient.getId());
        transfer.setRecipientEmail(recipient.getAccountNumber());
        transfer.setAmount(amount);
        transfer.setTotalDebited(totalDebit);
        // Fixed: was hardcoded Currency.KES. amount/totalDebit are both
        // computed directly against sender.getBalance()/recipient.getBalance()
        // above, and the wallet is fixed at USD -- so this transfer is
        // always genuinely USD, never KES.
        transfer.setCurrency(Currency.USD);
        transfer.setDescription(description);
        transfer.setStatus(TransferStatus.SUCCESS);
        transfer.setReference(reference);
        transfer.setInitiatedBy(initiatedBy);
        transferRepository.save(transfer);

        transferTransactionRecorder.record(transfer, sender, recipient);

        commissionService.recordCommission("COMMISSION_TRANSFER", commission,
                commissionService.getInternalTransferRate(), amount, "TRANSFER", transfer.getId(), reference,
                senderUserId, "Commission on transfer to " + recipient.getAccountNumber(), Currency.USD);

        // Transfer is always synchronous/immediate — no PENDING state, so
        // both parties are notified right here, right away, unlike the
        // gateway-based flows which wait for a webhook.
        // Name lookups are best-effort — see resolveNameSafely below;
        // either can come back null (lookup failed, or the account has
        // no name fields set), and EmailService already treats that as
        // "omit this row", never as an error.
        String recipientName = userNameResolver.resolveNameSafely(recipient.getAccountNumber());
        String senderName = userNameResolver.resolveNameSafely(sender.getAccountNumber());

        emailService.sendTransferNotification(sender.getAccountNumber(), amount.toPlainString(),
                transfer.getCurrency().name(), recipient.getAccountNumber(), recipientName, reference, true);
        emailService.sendTransferNotification(recipient.getAccountNumber(), amount.toPlainString(),
                transfer.getCurrency().name(), sender.getAccountNumber(), senderName, reference, false);

        log.info("Transfer completed | Sender: {} | Recipient: {} | Amount: {} | Commission: {} | TotalDebited: {} | Ref: {} | InitiatedBy: {}",
                sender.getAccountNumber(), recipient.getAccountNumber(), amount, commission, totalDebit, reference, initiatedBy);

        return new PaymentResponse(true, reference, "Transfer successful");
    }

    /**
     * GET /wallet/transfer/history — every transfer where this user is
     * EITHER the sender or the recipient, newest first. The `direction`
     * field on each record ("SENT"/"RECEIVED") is computed here at
     * mapping time, not stored on Transfer itself — Transfer has no
     * concept of "which side is the viewer," since the same record is
     * viewed from two different perspectives depending on who's asking.
     */
    public List<TransferRecordResponse> getTransferHistory(String userId) {
        return getTransferHistory(userId, null, null, null, null);
    }

    /**
     * Filtered version — status/direction/date-range all optional. Same
     * dynamic-Criteria approach as the other three history methods, with
     * one genuine difference: `direction` isn't a stored field at all
     * (see TransferRecordResponse's javadoc — it's computed relative to
     * the viewing user), so filtering on it means choosing WHICH field
     * gets matched, not adding an equality condition alongside userId:
     *   "SENT"     -> senderId = userId only
     *   "RECEIVED" -> recipientId = userId only
     *   omitted    -> senderId = userId OR recipientId = userId (today's
     *                 unfiltered behavior — every transfer this user is
     *                 party to, sent or received)
     */
    public List<TransferRecordResponse> getTransferHistory(String userId, TransferStatus status, String direction,
                                                             LocalDate fromDate, LocalDate toDate) {
        Criteria criteria;
        if ("SENT".equalsIgnoreCase(direction)) {
            criteria = Criteria.where("senderId").is(userId);
        } else if ("RECEIVED".equalsIgnoreCase(direction)) {
            criteria = Criteria.where("recipientId").is(userId);
        } else {
            criteria = new Criteria().orOperator(
                    Criteria.where("senderId").is(userId),
                    Criteria.where("recipientId").is(userId));
        }

        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        criteria = DateRangeCriteriaUtil.applyDateRange(criteria, "createdAt", fromDate, toDate);

        Query query = new Query(criteria).with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return mongoTemplate.find(query, Transfer.class).stream()
                .map(t -> toRecordResponse(t, userId))
                .toList();
    }

    /**
     * Admin-only: every transfer across every user, paginated — see
     * AdminFinanceController. `direction` is left null on each record —
     * unlike a user's own history, there's no single "viewing user" whose
     * perspective determines SENT vs RECEIVED when looking at everyone's
     * transfers at once; senderId/recipientId are already directly
     * visible on each record regardless.
     */
    public Page<TransferRecordResponse> getAllTransfers(Pageable pageable) {
        return transferRepository.findAll(pageable).map(t -> toRecordResponse(t, null));
    }

    private static TransferRecordResponse toRecordResponse(Transfer t, String viewingUserId) {
        TransferRecordResponse r = new TransferRecordResponse();
        r.setId(t.getId());
        r.setSenderId(t.getSenderId());
        r.setSenderEmail(t.getSenderEmail());
        r.setRecipientId(t.getRecipientId());
        r.setRecipientEmail(t.getRecipientEmail());
        r.setDirection(viewingUserId != null ? (viewingUserId.equals(t.getSenderId()) ? "SENT" : "RECEIVED") : null);
        r.setAmount(t.getAmount());
        r.setTotalDebited(t.getTotalDebited());
        r.setCurrency(t.getCurrency());
        r.setDescription(t.getDescription());
        r.setStatus(t.getStatus());
        r.setReference(t.getReference());
        r.setFailureReason(t.getFailureReason());
        r.setInitiatedBy(t.getInitiatedBy());
        r.setCreatedAt(t.getCreatedAt());
        return r;
    }
}