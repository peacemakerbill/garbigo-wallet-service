package com.premisave.wallet.service;

import com.premisave.wallet.dto.MpesaAsyncResponse;
import com.premisave.wallet.dto.MpesaReversalRequest;
import com.premisave.wallet.dto.TransactionStatusRequest;
import com.premisave.wallet.entity.GatewayBalanceSnapshot;
import com.premisave.wallet.exception.ResourceNotFoundException;
import com.premisave.wallet.entity.MpesaOperation;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.enums.MpesaOperationType;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.repository.MpesaOperationRepository;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import com.premisave.wallet.util.DateRangeCriteriaUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaOperationsService {

    private final MpesaOperationRepository operationRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final MpesaService mpesaService;
    private final IdempotencyService idempotencyService;
    private final GatewayBalanceSnapshotService gatewayBalanceSnapshotService;
    private final MongoTemplate mongoTemplate;

    // ─── Account Balance ─────────────────────────────────────────────────────

    @Transactional
    public MpesaAsyncResponse queryAccountBalance(String initiatedBy) {
        MpesaAsyncResponse result = mpesaService.queryAccountBalance();
        saveOperation(MpesaOperationType.ACCOUNT_BALANCE, initiatedBy, result, Map.of(), null);
        return result;
    }

    // ─── Transaction Status ──────────────────────────────────────────────────

    @Transactional
    public MpesaAsyncResponse queryTransactionStatus(String initiatedBy, TransactionStatusRequest request) {
        MpesaAsyncResponse result = mpesaService.queryTransactionStatus(request);
        Map<String, Object> summary = new HashMap<>();
        summary.put("transactionId", request.getTransactionId());
        summary.put("originalConversationId", request.getOriginalConversationId());
        saveOperation(MpesaOperationType.TRANSACTION_STATUS, initiatedBy, result, summary, null);
        return result;
    }

    // ─── Reversal ─────────────────────────────────────────────────────────────

    /**
     * Initiates a C2B reversal. If a completed DEPOSIT transaction is found
     * whose providerReference matches the given M-Pesa receipt, it's linked
     * so the wallet can be debited automatically once the reversal succeeds
     * (see completeOperation). If no matching transaction is found, the
     * reversal still proceeds — useful for M-Pesa payments that landed on
     * our shortcode without ever being credited to a wallet — but no
     * automatic wallet adjustment will happen.
     */
    @Transactional
    public MpesaAsyncResponse initiateReversal(String initiatedBy, MpesaReversalRequest request) {
        idempotencyService.checkIdempotency(request.getReference());

        Transaction original = transactionRepository.findByProviderReference(request.getTransactionId())
                .filter(tx -> tx.getStatus() == TransactionStatus.COMPLETED && tx.getType() == TransactionType.DEPOSIT)
                .orElse(null);

        MpesaAsyncResponse result = mpesaService.initiateReversal(request);

        Map<String, Object> summary = new HashMap<>();
        summary.put("transactionId", request.getTransactionId());
        summary.put("amount", request.getAmount());
        summary.put("reference", request.getReference());

        saveOperation(MpesaOperationType.REVERSAL, initiatedBy, result, summary,
                original != null ? original.getId() : null);

        if (original == null) {
            log.warn("Reversal initiated for M-Pesa receipt={} but no matching completed deposit " +
                    "transaction was found locally — wallet will NOT be auto-debited on success",
                    request.getTransactionId());
        }

        return result;
    }

    // ─── Reconciliation from Safaricom's ResultURL callback ─────────────────

    /**
     * Shared reconciliation entrypoint for AccountBalance, TransactionStatus,
     * and Reversal callbacks — all three share the same Result envelope
     * shape (see MpesaResultCallbackRequest), so PaymentCallbackController
     * routes all of them here regardless of which endpoint received them.
     */
    @Transactional
    public void completeOperation(String conversationId, boolean success, String resultCode,
                                   String resultDesc, Map<String, Object> resultParameters) {
        MpesaOperation op = operationRepository.findByConversationId(conversationId).orElse(null);
        if (op == null) {
            log.warn("M-Pesa operation callback for unknown ConversationID={} — ignoring", conversationId);
            return;
        }

        if (op.getStatus() != DisbursementStatus.PENDING) {
            log.warn("M-Pesa operation callback for already-finalized operation id={} status={} — ignoring duplicate",
                    op.getId(), op.getStatus());
            return;
        }

        op.setResultCode(resultCode);
        op.setResultDesc(resultDesc);
        op.setResultData(resultParameters);

        if (!success) {
            op.setStatus(DisbursementStatus.FAILED);
            operationRepository.save(op);

            // Saves a NEW snapshot rather than updating the original
            // PENDING_ASYNC one — same history-preserving,
            // never-overwritten pattern every other snapshot save
            // already follows; this new row becomes the "latest" one
            // an admin sees (findFirstByProviderOrderByCreatedAtDesc),
            // while the original PENDING_ASYNC row stays in history,
            // accurately reflecting what was known at that earlier moment.
            if (op.getType() == MpesaOperationType.ACCOUNT_BALANCE) {
                gatewayBalanceSnapshotService.save("MPESA", "ERROR", List.of(), resultDesc,
                        op.getConversationId(), op.getOriginatorConversationId(), op.getInitiatedBy());
            }

            log.warn("M-Pesa {} operation failed: id={} conversationId={} reason={}",
                    op.getType(), op.getId(), conversationId, resultDesc);
            return;
        }

        op.setStatus(DisbursementStatus.SUCCESS);
        operationRepository.save(op);
        log.info("M-Pesa {} operation completed: id={} conversationId={}", op.getType(), op.getId(), conversationId);

        if (op.getType() == MpesaOperationType.REVERSAL && op.getRelatedTransactionId() != null) {
            applyReversalToWallet(op, resultParameters);
        }

        if (op.getType() == MpesaOperationType.ACCOUNT_BALANCE) {
            saveRealAccountBalanceSnapshot(op, resultDesc, resultParameters);
        }
    }

    /**
     * Saves the REAL M-Pesa account balance data that arrives here via the
     * ResultURL webhook — genuinely different from the submission
     * acknowledgment ProviderBalanceService.getMpesaBalance saves
     * synchronously (PENDING_ASYNC, no real numbers). This is where the
     * actual figures Safaricom reports finally get persisted.
     *
     * Parses the "AccountBalance" ResultParameter — format confirmed
     * directly from a real captured sandbox callback plus Safaricom's own
     * Account Balance API documentation: pipe-delimited fields per
     * account, ampersand-separated between accounts, e.g. "Working
     * Account|KES|7.00|7.00|0.00|0.00&Utility Account|KES|2047.99|
     * 2047.99|0.00|0.00&...".
     *
     * Per Safaricom's own docs, the third field (index 2) is confirmed as
     * the account's available balance. The docs also reference "Uncleared
     * Funds" and "Reserved Funds" concepts, but the real sample shows
     * FOUR trailing numeric fields per account, not three, and the exact
     * field-by-field mapping beyond "available" isn't confirmed — those
     * are stored under generic sequential keys (value2, value3...) so the
     * raw data isn't lost, without asserting a specific meaning for each
     * one that isn't actually confirmed.
     *
     * "currency" on each saved entry holds the ACCOUNT NAME (e.g.
     * "Working Account"), not an ISO currency code — a deliberate
     * reinterpretation of that field for M-Pesa specifically, since
     * M-Pesa's own breakdown is genuinely by ACCOUNT (Working/Utility/
     * Charges Paid/Merchant/Organization Settlement), not by currency the
     * way the other four providers' balances are. All of them happen to
     * be KES in practice, so grouping by currency the way Stripe/PayPal/
     * Flutterwave/NOWPayments do would collapse every M-Pesa account into
     * a single meaningless KES bucket.
     */
    private void saveRealAccountBalanceSnapshot(MpesaOperation op, String resultDesc, Map<String, Object> resultParameters) {
        if (resultParameters == null) {
            return;
        }
        Object rawBalance = resultParameters.get("AccountBalance");
        if (rawBalance == null) {
            log.warn("M-Pesa Account Balance callback succeeded but had no 'AccountBalance' result parameter " +
                    "— nothing to save, conversationId={}", op.getConversationId());
            return;
        }

        List<GatewayBalanceSnapshot.CurrencyBalanceEntry> balances = parseMpesaAccountBalanceString(String.valueOf(rawBalance));
        gatewayBalanceSnapshotService.save("MPESA", "AVAILABLE", balances, resultDesc,
                op.getConversationId(), op.getOriginatorConversationId(), op.getInitiatedBy());

        log.info("M-Pesa real account balance saved: conversationId={} accounts={}",
                op.getConversationId(), balances.size());
    }

    private List<GatewayBalanceSnapshot.CurrencyBalanceEntry> parseMpesaAccountBalanceString(String raw) {
        List<GatewayBalanceSnapshot.CurrencyBalanceEntry> entries = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return entries;
        }

        for (String accountBlock : raw.split("&")) {
            String[] fields = accountBlock.split("\\|");
            if (fields.length < 3) {
                continue;
            }

            String accountName = fields[0].trim();
            String currencyCode = fields[1].trim();

            GatewayBalanceSnapshot.CurrencyBalanceEntry entry = new GatewayBalanceSnapshot.CurrencyBalanceEntry();
            entry.setCurrency(accountName + " (" + currencyCode + ")");

            Map<String, BigDecimal> amounts = new LinkedHashMap<>();
            try {
                amounts.put("available", new BigDecimal(fields[2].trim()));
            } catch (NumberFormatException e) {
                log.warn("M-Pesa AccountBalance field not parseable as a number: account={} value={}", accountName, fields[2]);
            }
            for (int i = 3; i < fields.length; i++) {
                try {
                    amounts.put("value" + (i - 1), new BigDecimal(fields[i].trim()));
                } catch (NumberFormatException ignored) {
                    // Not every trailing field is guaranteed numeric or present — skip rather than fail the whole entry.
                }
            }
            entry.setAmounts(amounts);
            entries.add(entry);
        }
        return entries;
    }

    private void applyReversalToWallet(MpesaOperation op, Map<String, Object> resultParameters) {
        Transaction original = transactionRepository.findById(op.getRelatedTransactionId()).orElse(null);
        if (original == null) {
            log.error("Reversal succeeded but original transaction id={} no longer exists — manual reconciliation needed",
                    op.getRelatedTransactionId());
            return;
        }

        Wallet wallet = walletRepository.findById(original.getWalletId()).orElse(null);
        if (wallet == null) {
            log.error("Reversal succeeded but wallet id={} no longer exists — manual reconciliation needed",
                    original.getWalletId());
            return;
        }

        BigDecimal amount = original.getAmount().abs();
        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);

        if (wallet.getBalance().compareTo(BigDecimal.ZERO) < 0) {
            log.warn("Wallet {} balance went negative ({}) after reversal debit — funds were likely already spent",
                    wallet.getId(), wallet.getBalance());
        }

        String mpesaTxId = resultParameters != null ? String.valueOf(resultParameters.getOrDefault("TransactionID", ""))
                : "";

        Transaction refund = new Transaction();
        refund.setUserId(wallet.getUserId());
        refund.setWalletId(wallet.getId());
        refund.setType(TransactionType.REFUND);
        refund.setStatus(TransactionStatus.COMPLETED);
        refund.setAmount(amount.negate());
        refund.setCurrency(original.getCurrency() != null ? original.getCurrency() : Currency.KES);
        refund.setDescription("Reversal of deposit " + original.getId() + " (M-Pesa receipt " + original.getProviderReference() + ")");
        refund.setReference(UUID.randomUUID().toString());
        refund.setProviderReference(mpesaTxId);
        transactionRepository.save(refund);

        log.info("Wallet {} debited {} following successful reversal of transaction {}",
                wallet.getId(), amount, original.getId());
    }

    // ─── Admin manual resolution of a stuck Reversal ─────────────────────────
    // Only REVERSAL genuinely has a wallet-affecting outcome among M-Pesa
    // operation types — Account Balance and Transaction Status are pure
    // queries with nothing to approve/reject at all, so no equivalent
    // methods exist for those, deliberately.

    /**
     * Manually resolves a stuck Reversal operation as SUCCESS — for when
     * an admin has independently confirmed via the M-Pesa portal that the
     * reversal genuinely went through, but the ResultURL callback that
     * would normally trigger this automatically never arrived. Reuses
     * applyReversalToWallet directly — the exact same wallet-debit logic
     * the automatic webhook path already runs — passing null for
     * resultParameters (there's no real callback payload for a manually
     * confirmed resolution); applyReversalToWallet already handles a null
     * resultParameters gracefully, just without a specific M-Pesa
     * transaction ID recorded on the resulting refund's providerReference.
     */
    @Transactional
    public MpesaOperation adminCompleteReversal(String operationId, String approvedBy) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required");
        }

        MpesaOperation op = operationRepository.findById(operationId)
                .orElseThrow(() -> new ResourceNotFoundException("M-Pesa operation not found: " + operationId));

        if (op.getType() != MpesaOperationType.REVERSAL) {
            throw new IllegalArgumentException(
                    "Only a REVERSAL operation can be manually completed this way — this operation is " + op.getType());
        }
        if (op.getStatus() != DisbursementStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Only a PENDING operation can be manually completed — this operation is already " + op.getStatus());
        }

        op.setStatus(DisbursementStatus.SUCCESS);
        op.setResultDesc("Manually completed by admin " + approvedBy);
        operationRepository.save(op);

        if (op.getRelatedTransactionId() != null) {
            applyReversalToWallet(op, null);
        }

        log.info("M-Pesa Reversal operation {} manually completed by admin={}", operationId, approvedBy);
        return op;
    }

    /**
     * Manually resolves a stuck Reversal operation as FAILED. No wallet
     * impact — nothing was debited/credited yet for a PENDING reversal,
     * same reasoning as every other admin manual-resolution method
     * tonight.
     */
    @Transactional
    public MpesaOperation adminRejectReversal(String operationId, String reason, String rejectedBy) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required");
        }

        MpesaOperation op = operationRepository.findById(operationId)
                .orElseThrow(() -> new ResourceNotFoundException("M-Pesa operation not found: " + operationId));

        if (op.getType() != MpesaOperationType.REVERSAL) {
            throw new IllegalArgumentException(
                    "Only a REVERSAL operation can be manually rejected this way — this operation is " + op.getType());
        }
        if (op.getStatus() != DisbursementStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Only a PENDING operation can be manually rejected — this operation is already " + op.getStatus());
        }

        op.setStatus(DisbursementStatus.FAILED);
        op.setResultDesc("Rejected by admin (" + rejectedBy + "): " + reason);
        operationRepository.save(op);

        log.info("M-Pesa Reversal operation {} manually rejected by admin={} reason={}",
                operationId, rejectedBy, reason);
        return op;
    }

    /**
     * Manually closes out a stuck non-Reversal operation (Account
     * Balance, Transaction Status) — these are pure queries with no
     * wallet-affecting outcome regardless of what actually happened, so
     * there's no meaningful approve-vs-reject distinction the way there
     * is for Reversal. This exists purely so an admin who's already
     * investigated a stuck operation (checked the M-Pesa portal directly,
     * confirmed there's nothing further to learn) can stop
     * flagStuckOperations' repeated 15-minute WARNING log spam for it —
     * that sweeper only re-flags operations still in PENDING, so closing
     * one here (marked FAILED — DisbursementStatus has no dedicated
     * "closed" value, and this reads correctly as "never got a real
     * result") removes it from future sweeps.
     *
     * Deliberately rejects REVERSAL here — that type genuinely does
     * affect a wallet and must go through adminCompleteReversal/
     * adminRejectReversal instead, not this generic, no-money-movement
     * close action.
     */
    @Transactional
    public MpesaOperation adminCloseOperation(String operationId, String note, String closedBy) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required");
        }

        MpesaOperation op = operationRepository.findById(operationId)
                .orElseThrow(() -> new ResourceNotFoundException("M-Pesa operation not found: " + operationId));

        if (op.getType() == MpesaOperationType.REVERSAL) {
            throw new IllegalArgumentException(
                    "REVERSAL operations affect a wallet and must be resolved via approve-reversal/reject-reversal, "
                            + "not this generic close action.");
        }
        if (op.getStatus() != DisbursementStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Only a PENDING operation can be closed — this operation is already " + op.getStatus());
        }

        op.setStatus(DisbursementStatus.FAILED);
        op.setResultDesc("Manually closed by admin " + closedBy
                + (note != null && !note.isBlank() ? ": " + note : " — no result ever received"));
        operationRepository.save(op);

        log.info("M-Pesa {} operation {} manually closed by admin={}", op.getType(), operationId, closedBy);
        return op;
    }

    public void markOperationTimedOut(String conversationId) {
        operationRepository.findByConversationId(conversationId).ifPresentOrElse(op ->
                log.warn("M-Pesa {} operation queue timeout: id={} conversationId={} — awaiting eventual result or manual reconciliation",
                        op.getType(), op.getId(), conversationId),
                () -> log.warn("Timeout callback for unknown ConversationID={}", conversationId));
    }

    // ─── Lookup for admins polling a submitted operation ────────────────────

    public MpesaOperation getOperation(String conversationId) {
        return operationRepository.findByConversationId(conversationId).orElse(null);
    }

    /**
     * Admin-only: every M-Pesa operation (Account Balance, Transaction
     * Status, Reversal, B2Pochi), paginated — same gap CommissionService's
     * getAllLedgerEntries closed for the company ledger: the repository
     * has always been able to hold this data, but nothing exposed it
     * through the API before this. Only getOperation(conversationId), a
     * single-record lookup, existed until now.
     *
     * type/status/date-range all optional, same dynamic-Criteria approach
     * as CommissionService/DepositService/DisbursementService's own admin
     * filtering. type/status are read as plain strings (matching how
     * every other admin filter in this codebase accepts them from a
     * controller) and safely upper-cased before matching against the
     * MpesaOperationType/DisbursementStatus enums stored on the document
     * — an unrecognized value simply matches nothing rather than
     * throwing, since Criteria.is() compares against the raw stored enum
     * value without needing Enum.valueOf() at all.
     */
    public Page<MpesaOperation> getAllOperations(String type, String status, LocalDate fromDate,
                                                  LocalDate toDate, Pageable pageable) {
        Criteria criteria = new Criteria();
        if (type != null && !type.isBlank()) {
            criteria = criteria.and("type").is(type.toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            criteria = criteria.and("status").is(status.toUpperCase());
        }
        criteria = DateRangeCriteriaUtil.applyDateRange(criteria, "createdAt", fromDate, toDate);

        long total = mongoTemplate.count(new Query(criteria), MpesaOperation.class);
        Query pagedQuery = new Query(criteria).with(pageable);
        List<MpesaOperation> content = mongoTemplate.find(pagedQuery, MpesaOperation.class);

        return new PageImpl<>(content, pageable, total);
    }

    // ─── Stuck-operation sweeper (mirrors DisbursementService's) ────────────

    @Scheduled(fixedDelay = 15 * 60 * 1000) // every 15 minutes
    public void flagStuckOperations() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        List<MpesaOperation> stuck = operationRepository.findByStatusAndCreatedAtBefore(
                DisbursementStatus.PENDING, cutoff);

        if (!stuck.isEmpty()) {
            log.warn("{} M-Pesa operation(s) stuck in PENDING beyond 30 minutes — needs manual reconciliation: {}",
                    stuck.size(), stuck.stream().map(MpesaOperation::getId).toList());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void saveOperation(MpesaOperationType type, String initiatedBy, MpesaAsyncResponse result,
                                Map<String, Object> requestSummary, String relatedTransactionId) {
        MpesaOperation op = new MpesaOperation();
        op.setType(type);
        op.setInitiatedBy(initiatedBy);
        op.setRequestSummary(requestSummary);
        op.setRelatedTransactionId(relatedTransactionId);
        String originatorId = blankToNull(result.getOriginatorConversationId());
        String conversationId = blankToNull(result.getConversationId());
        op.setOriginatorConversationId(originatorId);
        op.setConversationId(conversationId);
        op.setStatus(result.isSuccess() && conversationId != null
                ? DisbursementStatus.PENDING : DisbursementStatus.FAILED);
        if (!result.isSuccess()) {
            op.setResultDesc(result.getMessage());
        }
        operationRepository.save(op);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}