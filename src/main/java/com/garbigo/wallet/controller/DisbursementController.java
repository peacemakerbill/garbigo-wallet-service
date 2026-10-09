package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.B2PochiRequest;
import com.premisave.wallet.dto.DisbursementRecordResponse;
import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.dto.NowPaymentsVerifyRequest;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.service.DisbursementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/disbursements")
@RequiredArgsConstructor
public class DisbursementController {

    private final DisbursementService disbursementService;

    @PostMapping
    public ResponseEntity<ApiResponse<DisbursementResponse>> disburse(
            @Valid @RequestBody DisbursementRequest request,
            Authentication auth,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        DisbursementResponse response = disbursementService.processDisbursement(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Disbursement initiated", response));
    }

    /**
     * Withdraws from the caller's own Premisave wallet directly into their
     * own Pochi la Biashara business account (CommandID BusinessPayToPochi)
     * instead of their main M-Pesa balance. Same as the generic
     * POST /disbursements above — checks wallet exists/not frozen/sufficient
     * balance, debits the wallet up front, and refunds on failure — just a
     * different M-Pesa destination type. The recipient phone number is
     * always resolved from the caller's own verified profile, same as the
     * generic endpoint.
     * POST /disbursements/b2pochi
     */
    @PostMapping("/b2pochi")
    public ResponseEntity<ApiResponse<DisbursementResponse>> disburseToPochi(
            @Valid @RequestBody B2PochiRequest request,
            Authentication auth,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        DisbursementResponse response = disbursementService.processB2PochiPayment(userId, request);
        return ResponseEntity.ok(ApiResponse.success("B2Pochi disbursement initiated", response));
    }

    /**
     * Submits the 2FA code for a NOWPayments disbursement created via the
     * generic POST /disbursements above (provider=NOWPAYMENTS) — the step
     * that actually makes NOWPayments start processing the payout. Per
     * NOWPayments' own support docs, a disbursement created but never
     * verified is automatically rejected after roughly 1 hour.
     *
     * This endpoint deliberately doesn't care where verificationCode came
     * from — an authenticator app, an email, whatever your NOWPayments
     * account's Two-step authentication is actually set to (Dashboard →
     * Account settings). See DisbursementService.
     * verifyNowPaymentsDisbursement's javadoc for the full breakdown of
     * what each configuration means for whether this can be automated.
     *
     * Ownership-checked in the service layer — a caller can't verify (and
     * thereby trigger) a disbursement that isn't their own.
     * POST /disbursements/nowpayments/{disbursementId}/verify
     */
    @PostMapping("/nowpayments/{disbursementId}/verify")
    public ResponseEntity<ApiResponse<Void>> verifyNowPaymentsDisbursement(
            @PathVariable String disbursementId,
            @Valid @RequestBody NowPaymentsVerifyRequest request,
            Authentication auth,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        disbursementService.verifyNowPaymentsDisbursement(disbursementId, request.getVerificationCode(), userId);
        return ResponseEntity.ok(ApiResponse.success("Verification submitted — payout now processing"));
    }

    /**
     * Every disbursement for the authenticated user, across all five
     * providers, newest first. Naturally excludes admin-initiated B2B/B2C
     * top-up records without any extra filtering — those are stored under
     * the initiating admin's own userId, not any customer's, so a regular
     * user querying their own history would never see them regardless.
     * GET /disbursements/history
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<DisbursementRecordResponse>>> getHistory(
            @RequestParam(required = false) DisbursementStatus status,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Authentication auth, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        List<DisbursementRecordResponse> history = disbursementService.getDisbursementHistory(userId, status, provider, fromDate, toDate);
        return ResponseEntity.ok(ApiResponse.success("Disbursement history retrieved", history));
    }
}