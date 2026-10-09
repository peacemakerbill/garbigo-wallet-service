package com.premisave.wallet.controller;

import com.premisave.wallet.dto.*;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.MpesaOperation;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.service.AdminWalletService;
import com.premisave.wallet.service.DisbursementService;
import com.premisave.wallet.service.MpesaOperationsService;
import com.premisave.wallet.service.MpesaService;
import com.premisave.wallet.service.PullTransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/wallet")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE', 'OPERATIONS')")
public class AdminWalletController {

    private final AdminWalletService adminWalletService;
    private final DisbursementService disbursementService;
    private final MpesaOperationsService mpesaOperationsService;
    private final PullTransactionService pullTransactionService;
    private final MpesaService mpesaService;

    // ==================== WALLET MANAGEMENT ====================

    /** Wrapped in PagedModel explicitly — same PageImpl serialization fix confirmed necessary in AdminFinanceController; this endpoint just never got it applied. */
    @GetMapping("/wallets")
    public ResponseEntity<ApiResponse<PagedModel<WalletResponse>>> getAllWallets(Pageable pageable) {
        PagedModel<WalletResponse> body = new PagedModel<>(adminWalletService.getAllWallets(pageable));
        return ResponseEntity.ok(ApiResponse.success("Wallets retrieved successfully", body));
    }

    @GetMapping("/wallets/search")
    public ResponseEntity<ApiResponse<List<WalletResponse>>> searchWallets(@RequestParam String query) {
        return ResponseEntity.ok(ApiResponse.success("Wallets search results", 
                adminWalletService.searchWallets(query)));
    }

    @GetMapping("/wallets/{userId}")
    public ResponseEntity<ApiResponse<WalletResponse>> getWalletByUserId(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success("Wallet retrieved", 
                adminWalletService.getWalletByUserId(userId)));
    }

    @PutMapping("/wallets/{userId}/freeze")
    public ResponseEntity<ApiResponse<WalletResponse>> freezeWallet(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success("Wallet frozen successfully", 
                adminWalletService.freezeWallet(userId)));
    }

    @PutMapping("/wallets/{userId}/unfreeze")
    public ResponseEntity<ApiResponse<WalletResponse>> unfreezeWallet(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success("Wallet unfrozen successfully", 
                adminWalletService.unfreezeWallet(userId)));
    }

    // ==================== MANUAL ADJUSTMENTS ====================

    /**
     * Now takes Authentication (previously didn't at all) — resolved as
     * performedBy and passed through to AdminWalletService.creditWallet,
     * which records it on the new ManualAdjustment entity. reference in
     * the request body is accepted but ignored — see
     * ManualAdjustmentRequest's javadoc for why: the server always
     * generates its own now.
     */
    @PostMapping("/wallets/{userId}/credit")
    public ResponseEntity<ApiResponse<PaymentResponse>> creditWallet(
            @PathVariable String userId,
            @Valid @RequestBody ManualAdjustmentRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Wallet credited successfully", 
                adminWalletService.creditWallet(userId, request, auth.getName())));
    }

    /** Same change as creditWallet above. */
    @PostMapping("/wallets/{userId}/debit")
    public ResponseEntity<ApiResponse<PaymentResponse>> debitWallet(
            @PathVariable String userId,
            @Valid @RequestBody ManualAdjustmentRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Wallet debited successfully", 
                adminWalletService.debitWallet(userId, request, auth.getName())));
    }

    /**
     * Every manual adjustment (credit and debit), optionally filtered to
     * one user via ?userId=. Wrapped in PagedModel explicitly, not
     * returned as a raw Page<T> — confirmed necessary in this app via a
     * real redeploy (see AdminFinanceController's javadoc for the full
     * "Serializing PageImpl instances as-is is not supported" story).
     * GET /admin/wallet/adjustments?userId=&page=&size=&sort=
     */
    @GetMapping("/adjustments")
    public ResponseEntity<ApiResponse<PagedModel<ManualAdjustmentRecordResponse>>> getManualAdjustments(
            @RequestParam(required = false) String userId, Pageable pageable) {
        PagedModel<ManualAdjustmentRecordResponse> body =
                new PagedModel<>(adminWalletService.getManualAdjustments(userId, pageable));
        return ResponseEntity.ok(ApiResponse.success("Manual adjustments retrieved", body));
    }

    // ==================== TRANSACTIONS ====================

    /** Same fix as getAllWallets above. */
    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<PagedModel<TransactionResponse>>> getAllTransactions(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            Pageable pageable) {
        
        PagedModel<TransactionResponse> body = new PagedModel<>(
                adminWalletService.getAllTransactions(userId, type, status, fromDate, toDate, pageable));
        return ResponseEntity.ok(ApiResponse.success("Transactions retrieved", body));
    }

    @GetMapping("/transactions/{transactionId}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTransactionById(@PathVariable String transactionId) {
        return ResponseEntity.ok(ApiResponse.success("Transaction retrieved", 
                adminWalletService.getTransactionById(transactionId)));
    }

    // ==================== DISBURSEMENTS ====================

    @GetMapping("/disbursements/pending")
    public ResponseEntity<ApiResponse<List<Disbursement>>> getPendingDisbursements() {
        return ResponseEntity.ok(ApiResponse.success("Pending disbursements retrieved", 
                adminWalletService.getPendingDisbursements()));
    }

    // ==================== B2B (BUSINESS TO BUSINESS) ====================

    @PostMapping("/b2b/pay")
    public ResponseEntity<ApiResponse<DisbursementResponse>> payB2B(
            @Valid @RequestBody MpesaB2BRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("B2B payment initiated",
                adminWalletService.processB2BPayment(auth.getName(), request)));
    }

    @PostMapping("/b2b/query-org-info")
    public ResponseEntity<ApiResponse<QueryOrgInfoResponse>> queryOrgInfo(
            @Valid @RequestBody QueryOrgInfoRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Query Org Info lookup complete",
                mpesaService.queryOrgInfo(request)));
    }

    // ==================== B2POCHI (BUSINESS TO POCHI LA BIASHARA) ====================

    @PostMapping("/mpesa/b2pochi/pay")
    public ResponseEntity<ApiResponse<DisbursementResponse>> payB2Pochi(
            @Valid @RequestBody B2PochiRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("B2Pochi payment initiated",
                disbursementService.processB2PochiPayment(auth.getName(), request)));
    }

    // ==================== M-PESA ACCOUNT BALANCE ====================

    @PostMapping("/mpesa/balance/query")
    public ResponseEntity<ApiResponse<MpesaAsyncResponse>> queryAccountBalance(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Account balance query submitted",
                mpesaOperationsService.queryAccountBalance(auth.getName())));
    }

    // ==================== M-PESA TRANSACTION STATUS ====================

    @PostMapping("/mpesa/transaction-status/query")
    public ResponseEntity<ApiResponse<MpesaAsyncResponse>> queryTransactionStatus(
            @RequestBody TransactionStatusRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Transaction status query submitted",
                mpesaOperationsService.queryTransactionStatus(auth.getName(), request)));
    }

    // ==================== M-PESA REVERSAL ====================

    @PostMapping("/mpesa/reversal")
    public ResponseEntity<ApiResponse<MpesaAsyncResponse>> reverseTransaction(
            @Valid @RequestBody MpesaReversalRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Reversal submitted",
                mpesaOperationsService.initiateReversal(auth.getName(), request)));
    }

    // ==================== M-PESA OPERATIONS LOOKUP ====================

    @GetMapping("/mpesa/operations/{conversationId}")
    public ResponseEntity<ApiResponse<MpesaOperation>> getMpesaOperation(@PathVariable String conversationId) {
        MpesaOperation operation = mpesaOperationsService.getOperation(conversationId);
        if (operation == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("No operation found for conversationId: " + conversationId));
        }
        return ResponseEntity.ok(ApiResponse.success("Operation retrieved", operation));
    }

    // ==================== M-PESA PULL TRANSACTIONS (C2B RECONCILIATION) ====================

    @PostMapping("/mpesa/pull/register")
    public ResponseEntity<ApiResponse<PullTransactionResponse>> registerPullTransactions() {
        return ResponseEntity.ok(ApiResponse.success("Pull Transactions registration submitted",
                pullTransactionService.register()));
    }

    @PostMapping("/mpesa/pull/query")
    public ResponseEntity<ApiResponse<PullTransactionResponse>> queryPullTransactions(
            @RequestBody(required = false) PullTransactionQueryRequest request) {
        PullTransactionResponse result = (request == null
                || request.getStartDate() == null || request.getEndDate() == null)
                ? pullTransactionService.queryAndReconcileDefault()
                : pullTransactionService.queryAndReconcile(request.getStartDate(), request.getEndDate(),
                        request.getOffsetValue() != null ? request.getOffsetValue() : 0);

        return ResponseEntity.ok(ApiResponse.success("Pull Transactions query and reconciliation complete", result));
    }

    // ==================== REPORTS & ANALYTICS ====================

    @GetMapping("/reports/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSystemSummary() {
        return ResponseEntity.ok(ApiResponse.success("System financial summary", 
                adminWalletService.getSystemSummary()));
    }

    @GetMapping("/reports/daily")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDailyReport(@RequestParam LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success("Daily report retrieved", 
                adminWalletService.getDailyReport(date)));
    }

    @GetMapping("/reports/balance-overview")
    public ResponseEntity<ApiResponse<Map<String, BigDecimal>>> getTotalBalanceOverview() {
        return ResponseEntity.ok(ApiResponse.success("Total balance overview", 
                adminWalletService.getTotalBalanceOverview()));
    }
}