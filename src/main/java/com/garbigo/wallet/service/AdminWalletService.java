package com.premisave.wallet.service;

import com.premisave.wallet.dto.*;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.ManualAdjustment;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.enums.ManualAdjustmentType;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.ManualAdjustmentRepository;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminWalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final DisbursementRepository disbursementRepository;
    private final ManualAdjustmentRepository manualAdjustmentRepository;
    private final WalletService walletService;
    private final DisbursementService disbursementService;

    public Page<WalletResponse> getAllWallets(Pageable pageable) {
        return walletRepository.findAll(pageable).map(this::mapToWalletResponse);
    }

    public List<WalletResponse> searchWallets(String query) {
        return walletRepository.findAll().stream()
                .filter(w -> w.getAccountNumber().toLowerCase().contains(query.toLowerCase()) ||
                             w.getUserId().toLowerCase().contains(query.toLowerCase()))
                .map(this::mapToWalletResponse)
                .toList();
    }

    public WalletResponse getWalletByUserId(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));
        return mapToWalletResponse(wallet);
    }

    public WalletResponse freezeWallet(String userId) {
        return walletService.freezeWallet(userId);
    }

    public WalletResponse unfreezeWallet(String userId) {
        return walletService.unfreezeWallet(userId);
    }

    /**
     * Now creates a dedicated ManualAdjustment record — mirrors Deposit/
     * Disbursement/Transfer/Payment: a real entity instead of only the
     * bare Transaction row this used to create directly, with
     * balanceBefore/balanceAfter captured explicitly (unique to this
     * entity — a manual adjustment is a human unilaterally overriding a
     * balance, not a normal transactional event, so an unambiguous
     * before/after record matters more here than anywhere else).
     *
     * reference is ALWAYS server-generated now ("ADJ-" + a random UUID),
     * regardless of what request.getReference() holds — see
     * ManualAdjustmentRequest's javadoc for why. The same generated
     * reference is used on both the ManualAdjustment record and the
     * Transaction row, so the two stay cross-referenceable.
     *
     * performedBy is new — resolved from the calling admin's own JWT by
     * AdminWalletController (auth.getName()), never taken from the
     * request body. The credit/debit controller methods previously took
     * no Authentication parameter at all, so there was no way to
     * attribute WHICH admin performed a given adjustment.
     */
    @Transactional
    public PaymentResponse creditWallet(String userId, ManualAdjustmentRequest request, String performedBy) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found"));

        BigDecimal balanceBefore = wallet.getBalance();
        BigDecimal balanceAfter = balanceBefore.add(request.getAmount());
        wallet.setBalance(balanceAfter);
        walletRepository.save(wallet);

        String reference = "ADJ-" + UUID.randomUUID();

        ManualAdjustment adjustment = new ManualAdjustment();
        adjustment.setUserId(userId);
        adjustment.setWalletId(wallet.getId());
        adjustment.setAccountNumber(wallet.getAccountNumber());
        adjustment.setType(ManualAdjustmentType.CREDIT);
        adjustment.setAmount(request.getAmount());
        adjustment.setCurrency(Currency.USD);
        adjustment.setBalanceBefore(balanceBefore);
        adjustment.setBalanceAfter(balanceAfter);
        adjustment.setReason(request.getReason());
        adjustment.setReference(reference);
        adjustment.setPerformedBy(performedBy);
        manualAdjustmentRepository.save(adjustment);

        Transaction tx = createAdjustmentTransaction(wallet, TransactionType.DEPOSIT, request.getAmount(),
                "Admin Credit: " + request.getReason(), reference);
        transactionRepository.save(tx);

        log.info("Admin credited wallet {} with {} - Reason: {} - PerformedBy: {} - Ref: {}",
                userId, request.getAmount(), request.getReason(), performedBy, reference);
        return new PaymentResponse(true, tx.getId(), "Credit successful");
    }

    /** Same migration as creditWallet above — see its javadoc for full reasoning. */
    @Transactional
    public PaymentResponse debitWallet(String userId, ManualAdjustmentRequest request, String performedBy) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found"));

        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException("Insufficient balance for debit");
        }

        BigDecimal balanceBefore = wallet.getBalance();
        BigDecimal balanceAfter = balanceBefore.subtract(request.getAmount());
        wallet.setBalance(balanceAfter);
        walletRepository.save(wallet);

        String reference = "ADJ-" + UUID.randomUUID();

        ManualAdjustment adjustment = new ManualAdjustment();
        adjustment.setUserId(userId);
        adjustment.setWalletId(wallet.getId());
        adjustment.setAccountNumber(wallet.getAccountNumber());
        adjustment.setType(ManualAdjustmentType.DEBIT);
        adjustment.setAmount(request.getAmount());
        adjustment.setCurrency(Currency.USD);
        adjustment.setBalanceBefore(balanceBefore);
        adjustment.setBalanceAfter(balanceAfter);
        adjustment.setReason(request.getReason());
        adjustment.setReference(reference);
        adjustment.setPerformedBy(performedBy);
        manualAdjustmentRepository.save(adjustment);

        Transaction tx = createAdjustmentTransaction(wallet, TransactionType.WITHDRAWAL, request.getAmount().negate(),
                "Admin Debit: " + request.getReason(), reference);
        transactionRepository.save(tx);

        log.info("Admin debited wallet {} with {} - Reason: {} - PerformedBy: {} - Ref: {}",
                userId, request.getAmount(), request.getReason(), performedBy, reference);
        return new PaymentResponse(true, tx.getId(), "Debit successful");
    }

    /**
     * GET /admin/wallet/adjustments — every manual adjustment, optionally
     * filtered to one user. userId is a query param, not a path segment,
     * matching how getAllTransactions above already takes it (optional,
     * flat) rather than nesting under /wallets/{userId}/.
     */
    public Page<ManualAdjustmentRecordResponse> getManualAdjustments(String userId, Pageable pageable) {
        Page<ManualAdjustment> page = (userId != null && !userId.isBlank())
                ? manualAdjustmentRepository.findByUserId(userId, pageable)
                : manualAdjustmentRepository.findAll(pageable);
        return page.map(AdminWalletService::toAdjustmentRecordResponse);
    }

    private static ManualAdjustmentRecordResponse toAdjustmentRecordResponse(ManualAdjustment a) {
        ManualAdjustmentRecordResponse r = new ManualAdjustmentRecordResponse();
        r.setId(a.getId());
        r.setUserId(a.getUserId());
        r.setAccountNumber(a.getAccountNumber());
        r.setType(a.getType());
        r.setAmount(a.getAmount());
        r.setCurrency(a.getCurrency());
        r.setBalanceBefore(a.getBalanceBefore());
        r.setBalanceAfter(a.getBalanceAfter());
        r.setReason(a.getReason());
        r.setReference(a.getReference());
        r.setPerformedBy(a.getPerformedBy());
        r.setCreatedAt(a.getCreatedAt());
        return r;
    }

    /**
     * FIXED: Proper filtered pagination with Spring Data Pageable
     */
    public Page<TransactionResponse> getAllTransactions(String userId, TransactionType type,
                                                        TransactionStatus status, LocalDate fromDate,
                                                        LocalDate toDate, Pageable pageable) {

        List<Transaction> transactions;

        if (userId != null && !userId.isBlank()) {
            transactions = transactionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        } else {
            transactions = transactionRepository.findAll();
        }

        // Apply filters
        List<Transaction> filtered = transactions.stream()
                .filter(tx -> type == null || tx.getType() == type)
                .filter(tx -> status == null || tx.getStatus() == status)
                .filter(tx -> fromDate == null || tx.getCreatedAt().toLocalDate().isAfter(fromDate.minusDays(1)))
                .filter(tx -> toDate == null || tx.getCreatedAt().toLocalDate().isBefore(toDate.plusDays(1)))
                .collect(Collectors.toList());

        // Convert to Page
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), filtered.size());

        List<TransactionResponse> content = filtered.subList(start, end).stream()
                .map(this::mapToTransactionResponse)
                .collect(Collectors.toList());

        return new PageImpl<>(content, pageable, filtered.size());
    }

    public TransactionResponse getTransactionById(String transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + transactionId));
        return mapToTransactionResponse(tx);
    }

    public List<Disbursement> getPendingDisbursements() {
        return disbursementRepository.findAll().stream()
                .filter(d -> d.getStatus() == DisbursementStatus.PENDING)
                .toList();
    }

    /**
     * approveDisbursement/rejectDisbursement moved to
     * AdminReconciliationController, calling DisbursementService.
     * adminApproveDisbursement/adminRejectDisbursement directly —
     * consolidated alongside Deposit and M-Pesa Reversal's own
     * reconciliation actions rather than living here separately.
     */

    // ==================== B2B ====================

    /**
     * Delegates to DisbursementService, which owns the M-Pesa B2B call,
     * Disbursement record creation, and async result reconciliation.
     */
    public DisbursementResponse processB2BPayment(String initiatedByUserId, MpesaB2BRequest request) {
        return disbursementService.processB2BPayment(initiatedByUserId, request);
    }

    // ==================== REPORTS ====================

    public Map<String, Object> getSystemSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalWallets", walletRepository.count());
        summary.put("totalBalance", walletRepository.findAll().stream()
                .map(Wallet::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add));
        summary.put("totalTransactions", transactionRepository.count());
        return summary;
    }

    public Map<String, Object> getDailyReport(LocalDate date) {
        return Map.of(
            "date", date,
            "totalDeposits", BigDecimal.ZERO,
            "totalWithdrawals", BigDecimal.ZERO,
            "totalTransfers", BigDecimal.ZERO
        );
    }

    public Map<String, BigDecimal> getTotalBalanceOverview() {
        BigDecimal total = walletRepository.findAll().stream()
                .map(Wallet::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("totalSystemBalance", total);
    }

    private Transaction createAdjustmentTransaction(Wallet wallet, TransactionType type, BigDecimal amount,
                                                    String description, String reference) {
        Transaction tx = new Transaction();
        tx.setUserId(wallet.getUserId());
        tx.setWalletId(wallet.getId());
        tx.setType(type);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setCurrency(Currency.USD);
        tx.setDescription(description);
        tx.setReference(reference);
        return tx;
    }

    private WalletResponse mapToWalletResponse(Wallet wallet) {
        WalletResponse response = new WalletResponse();
        response.setId(wallet.getId());
        response.setAccountNumber(wallet.getAccountNumber());
        response.setUserId(wallet.getUserId());
        response.setBalance(wallet.getBalance());
        response.setCurrency(wallet.getCurrency());
        response.setFrozen(wallet.isFrozen());
        return response;
    }

    private TransactionResponse mapToTransactionResponse(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getType(),
                tx.getStatus(),
                tx.getAmount(),
                tx.getCurrency() != null ? tx.getCurrency().name() : "KES",
                tx.getDescription(),
                tx.getCreatedAt()
        );
    }
}