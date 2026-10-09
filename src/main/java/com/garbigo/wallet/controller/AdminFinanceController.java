package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.BalanceOverviewResponse;
import com.premisave.wallet.dto.DailyFinanceReportResponse;
import com.premisave.wallet.dto.SystemSummaryResponse;
import com.premisave.wallet.dto.DepositRecordResponse;
import com.premisave.wallet.dto.DisbursementRecordResponse;
import com.premisave.wallet.dto.PaymentRecordResponse;
import com.premisave.wallet.dto.TransferRecordResponse;
import com.premisave.wallet.entity.CompanyLedgerEntry;
import com.premisave.wallet.enums.DepositStatus;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.service.AdminReportService;
import com.premisave.wallet.service.CommissionService;
import com.premisave.wallet.service.DepositService;
import com.premisave.wallet.service.DisbursementService;
import com.premisave.wallet.service.PaymentService;
import com.premisave.wallet.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Admin-only, cross-user visibility into every financial entity built
 * alongside tonight's Deposit/Disbursement/Transfer/Payment/
 * CompanyLedgerEntry work — Deposit, Disbursement, Transfer, Payment, and
 * the company P&L ledger.
 *
 * Deliberately a SEPARATE controller from AdminWalletController, not an
 * addition to it — AdminWalletController's own service
 * (AdminWalletService) was never actually seen in this session, only
 * called into from that controller's existing methods. Extending it
 * blind would mean guessing at its real constructor dependencies, import
 * list, and existing multi-field filtering implementation, none of which
 * were ever verified. This controller instead calls straight into the
 * same DepositService/DisbursementService/TransferService/PaymentService/
 * CommissionService already built and verified earlier tonight — no new
 * service logic invented, just new admin-facing entry points onto it.
 *
 * Same security posture as AdminWalletController — ADMIN/FINANCE/OPERATIONS
 * only.
 *
 * Pagination follows the exact convention AdminWalletController's own
 * GET /admin/wallet/transactions already uses — a bare Pageable parameter,
 * resolved automatically from query params like ?page=0&size=20&sort=createdAt,desc.
 *
 * EVERY method wraps its Page<T> result in org.springframework.data.web.
 * PagedModel<T> explicitly, rather than returning Page<T> directly.
 * First attempt at this used the global @EnableSpringDataWebSupport
 * (pageSerializationMode = VIA_DTO) annotation instead — syntactically
 * correct per Spring's own documentation, but confirmed NOT to actually
 * suppress the "Serializing PageImpl instances as-is is not supported"
 * warning in THIS app (verified via a real redeploy — fresh PID, same
 * warning, first request). Rather than debug why the global config isn't
 * taking effect here (this app logs "Multiple Spring Data modules found,
 * entering strict repository configuration mode" at startup, and there
 * are documented cases of this annotation interacting unpredictably with
 * other Spring Data configuration present in an app), switched to this
 * explicit, per-method wrapping instead — it can't fail the same way,
 * since it doesn't depend on any global Jackson module registration
 * succeeding correctly.
 */
@RestController
@RequestMapping("/admin/finance")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE', 'OPERATIONS')")
public class AdminFinanceController {

    private final DepositService depositService;
    private final DisbursementService disbursementService;
    private final TransferService transferService;
    private final PaymentService paymentService;
    private final CommissionService commissionService;
    private final AdminReportService adminReportService;

    /**
     * Comprehensive daily financial report — built fresh against every
     * entity from tonight's work (Deposit/Disbursement/Transfer/Payment/
     * CompanyLedgerEntry), not an extension of AdminWalletController's
     * existing getDailyReport, whose real implementation was never seen
     * in this session. Deliberately a different URL
     * (/admin/finance/reports/daily, not /admin/wallet/reports/daily) —
     * see AdminReportService's javadoc for the full reasoning. Malformed
     * date input (e.g. ?date=2026 or ?date=2026-08-8) now returns a clean
     * 400 with a clear message instead of a raw stack trace — see
     * GlobalExceptionHandler.handleTypeMismatch.
     */
    @GetMapping("/reports/daily")
    public ResponseEntity<ApiResponse<DailyFinanceReportResponse>> getDailyReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        DailyFinanceReportResponse report = adminReportService.getDailyReport(date);
        return ResponseEntity.ok(ApiResponse.success("Daily report retrieved", report));
    }

    /**
     * Detailed platform balance snapshot — total/active/frozen balances,
     * provider-linkage adoption counts, all-time commission revenue, and
     * the top N wallets by balance. topN defaults to 10 if omitted.
     */
    @GetMapping("/reports/balance-overview")
    public ResponseEntity<ApiResponse<BalanceOverviewResponse>> getBalanceOverview(
            @RequestParam(defaultValue = "10") int topN) {
        BalanceOverviewResponse overview = adminReportService.getBalanceOverview(topN);
        return ResponseEntity.ok(ApiResponse.success("Balance overview retrieved", overview));
    }

    /**
     * All-time platform summary — total volume ever processed across
     * every entity, status breakdowns, provider breakdowns, and all-time
     * commission revenue. Distinct in scope from the daily report (one
     * day) and balance overview (current wallet state).
     */
    @GetMapping("/reports/summary")
    public ResponseEntity<ApiResponse<SystemSummaryResponse>> getSystemSummary() {
        SystemSummaryResponse summary = adminReportService.getSystemSummary();
        return ResponseEntity.ok(ApiResponse.success("System summary retrieved", summary));
    }

    /**
     * userId/status/provider/fromDate/toDate all optional — omitting all
     * of them returns the same full, unfiltered result as before this
     * filtering was added.
     * GET /admin/finance/deposits?status=SUCCESS&provider=STRIPE&fromDate=2026-08-01&toDate=2026-08-21
     */
    @GetMapping("/deposits")
    public ResponseEntity<ApiResponse<PagedModel<DepositRecordResponse>>> getAllDeposits(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) DepositStatus status,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Pageable pageable) {
        PagedModel<DepositRecordResponse> body = new PagedModel<>(
                depositService.getAllDeposits(userId, status, provider, fromDate, toDate, pageable));
        return ResponseEntity.ok(ApiResponse.success("Deposits retrieved", body));
    }

    /**
     * userId/status/provider/fromDate/toDate all optional — omitting all
     * of them returns the same full, unfiltered result as before this
     * filtering was added.
     * GET /admin/finance/disbursements?status=FAILED&provider=MPESA&fromDate=2026-08-01&toDate=2026-08-21
     */
    @GetMapping("/disbursements")
    public ResponseEntity<ApiResponse<PagedModel<DisbursementRecordResponse>>> getAllDisbursements(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) DisbursementStatus status,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Pageable pageable) {
        PagedModel<DisbursementRecordResponse> body = new PagedModel<>(
                disbursementService.getAllDisbursements(userId, status, provider, fromDate, toDate, pageable));
        return ResponseEntity.ok(ApiResponse.success("Disbursements retrieved", body));
    }

    @GetMapping("/transfers")
    public ResponseEntity<ApiResponse<PagedModel<TransferRecordResponse>>> getAllTransfers(Pageable pageable) {
        PagedModel<TransferRecordResponse> body = new PagedModel<>(transferService.getAllTransfers(pageable));
        return ResponseEntity.ok(ApiResponse.success("Transfers retrieved", body));
    }

    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<PagedModel<PaymentRecordResponse>>> getAllPayments(Pageable pageable) {
        PagedModel<PaymentRecordResponse> body = new PagedModel<>(paymentService.getAllPayments(pageable));
        return ResponseEntity.ok(ApiResponse.success("Payments retrieved", body));
    }

    /**
     * The company profit/loss ledger — every commission entry recorded
     * from transfers and gateway disbursements since this system existed,
     * previously visible only via direct MongoDB query.
     *
     * userId/type/fromDate/toDate all optional — omitting all of them
     * returns the same full, unfiltered result as before this filtering
     * was added.
     * GET /admin/finance/ledger?type=COMPANY_DISBURSEMENT&fromDate=2026-08-01&toDate=2026-08-21
     */
    @GetMapping("/ledger")
    public ResponseEntity<ApiResponse<PagedModel<CompanyLedgerEntry>>> getLedger(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Pageable pageable) {
        PagedModel<CompanyLedgerEntry> body = new PagedModel<>(
                commissionService.getAllLedgerEntries(userId, type, fromDate, toDate, pageable));
        return ResponseEntity.ok(ApiResponse.success("Company ledger retrieved", body));
    }
}