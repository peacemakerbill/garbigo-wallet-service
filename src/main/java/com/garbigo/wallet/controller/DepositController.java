package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.DepositRecordResponse;
import com.premisave.wallet.enums.DepositStatus;
import com.premisave.wallet.service.DepositService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only deposit history — first standalone controller for Deposit,
 * mirroring how DisbursementController/PaymentController already exist as
 * their own dedicated controllers rather than being folded into
 * WalletController.
 */
@RestController
@RequestMapping("/deposits")
@RequiredArgsConstructor
public class DepositController {

    private final DepositService depositService;

    /**
     * Every deposit for the authenticated user, across all five providers,
     * newest first. All four query params are optional — omitting all of
     * them returns the same full history as before this filtering was
     * added.
     * GET /deposits/history?status=SUCCESS&provider=STRIPE&fromDate=2026-08-01&toDate=2026-08-18
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<DepositRecordResponse>>> getHistory(
            @RequestParam(required = false) DepositStatus status,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Authentication auth, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        List<DepositRecordResponse> history = depositService.getDepositHistory(userId, status, provider, fromDate, toDate);
        return ResponseEntity.ok(ApiResponse.success("Deposit history retrieved", history));
    }
}