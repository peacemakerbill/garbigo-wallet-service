package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.DepositRecordResponse;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.dto.PendingReconciliationItem;
import com.premisave.wallet.entity.MpesaOperation;
import com.premisave.wallet.service.DepositService;
import com.premisave.wallet.service.DisbursementService;
import com.premisave.wallet.service.MpesaOperationsService;
import com.premisave.wallet.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Cross-entity reconciliation — a genuine gap this fills: previously the
 * only way to see "everything currently stuck across the whole system"
 * was scanning application logs for each entity's own 15-minute sweeper
 * warning separately (MpesaOperationsService.flagStuckOperations,
 * DisbursementService.flagStuckDisbursements). This surfaces all of it
 * in one place.
 *
 * Disbursement's approve/reject moved HERE from AdminWalletController —
 * consolidated alongside Deposit and M-Pesa's own reconciliation actions
 * rather than living in a separate controller. Same underlying
 * DisbursementService.adminApproveDisbursement/adminRejectDisbursement
 * logic, unchanged — only the URL and which controller hosts it changed.
 *
 * Deliberately does NOT include Transfer/Payment resolution — see
 * ReconciliationService's own javadoc for exactly why. The dashboard
 * still LISTS these for visibility; it just doesn't offer an action for
 * them yet.
 *
 * Same ADMIN/FINANCE/OPERATIONS security posture as every other admin
 * controller tonight.
 */
@RestController
@RequestMapping("/admin/reconciliation")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE', 'OPERATIONS')")
public class AdminReconciliationController {

    private final ReconciliationService reconciliationService;
    private final DepositService depositService;
    private final DisbursementService disbursementService;
    private final MpesaOperationsService mpesaOperationsService;

    /** Everything currently PENDING, across Deposit/Disbursement/Transfer/Payment/M-Pesa operations, oldest first. */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<PendingReconciliationItem>>> getAllPending() {
        return ResponseEntity.ok(ApiResponse.success("Pending items retrieved", reconciliationService.getAllPending()));
    }

    /**
     * Admin-only: every M-Pesa operation (Account Balance, Transaction
     * Status, Reversal, B2Pochi) ever recorded, paginated — the actual
     * "fetch all M-Pesa operations from the DB" this was built for.
     * Previously only /pending existed, which mixes M-Pesa operations in
     * with pending Deposits/Disbursements/Transfers/Payments and only
     * ever shows currently-pending ones, never a full history.
     *
     * type/status/fromDate/toDate all optional — omitting all of them
     * returns the full, unfiltered history.
     * GET /admin/reconciliation/mpesa-operations?type=REVERSAL&status=FAILED&fromDate=2026-08-01&toDate=2026-08-29
     */
    @GetMapping("/mpesa-operations")
    public ResponseEntity<ApiResponse<PagedModel<MpesaOperation>>> getAllOperations(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Pageable pageable) {
        PagedModel<MpesaOperation> body = new PagedModel<>(
                mpesaOperationsService.getAllOperations(type, status, fromDate, toDate, pageable));
        return ResponseEntity.ok(ApiResponse.success("M-Pesa operations retrieved", body));
    }

    // ─── Deposit resolution ──────────────────────────────────────────────────

    @PostMapping("/deposits/{depositId}/approve")
    public ResponseEntity<ApiResponse<DepositRecordResponse>> approveDeposit(
            @PathVariable String depositId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Deposit approved",
                depositService.adminApproveDeposit(depositId, auth.getName())));
    }

    @PostMapping("/deposits/{depositId}/reject")
    public ResponseEntity<ApiResponse<DepositRecordResponse>> rejectDeposit(
            @PathVariable String depositId, @RequestParam String reason, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Deposit rejected",
                depositService.adminRejectDeposit(depositId, reason, auth.getName())));
    }

    // ─── Disbursement resolution ─────────────────────────────────────────────
    // Moved from AdminWalletController — same underlying
    // DisbursementService methods, unchanged logic, new location only.

    @PostMapping("/disbursements/{disbursementId}/approve")
    public ResponseEntity<ApiResponse<DisbursementResponse>> approveDisbursement(
            @PathVariable String disbursementId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Disbursement approved",
                disbursementService.adminApproveDisbursement(disbursementId, auth.getName())));
    }

    @PostMapping("/disbursements/{disbursementId}/reject")
    public ResponseEntity<ApiResponse<DisbursementResponse>> rejectDisbursement(
            @PathVariable String disbursementId, @RequestParam String reason, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Disbursement rejected",
                disbursementService.adminRejectDisbursement(disbursementId, reason, auth.getName())));
    }

    // ─── M-Pesa Reversal resolution ──────────────────────────────────────────
    // Only Reversal genuinely affects a wallet — see
    // ReconciliationService/MpesaOperationsService javadocs.

    @PostMapping("/mpesa-operations/{operationId}/approve-reversal")
    public ResponseEntity<ApiResponse<MpesaOperation>> approveReversal(
            @PathVariable String operationId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Reversal manually completed",
                mpesaOperationsService.adminCompleteReversal(operationId, auth.getName())));
    }

    @PostMapping("/mpesa-operations/{operationId}/reject-reversal")
    public ResponseEntity<ApiResponse<MpesaOperation>> rejectReversal(
            @PathVariable String operationId, @RequestParam String reason, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Reversal manually rejected",
                mpesaOperationsService.adminRejectReversal(operationId, reason, auth.getName())));
    }

    // ─── Generic close — any non-Reversal M-Pesa operation ───────────────────
    // Account Balance, Transaction Status, or any future non-Reversal type
    // — no wallet-affecting outcome, so no approve/reject distinction;
    // this just stops flagStuckOperations' repeated log warnings for an
    // operation an admin has already investigated. note is optional.

    @PostMapping("/mpesa-operations/{operationId}/close")
    public ResponseEntity<ApiResponse<MpesaOperation>> closeOperation(
            @PathVariable String operationId,
            @RequestParam(required = false) String note,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Operation closed",
                mpesaOperationsService.adminCloseOperation(operationId, note, auth.getName())));
    }
}