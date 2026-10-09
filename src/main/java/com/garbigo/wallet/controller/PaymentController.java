package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.PaymentInitiateRequest;
import com.premisave.wallet.dto.PaymentRecordResponse;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.enums.PaymentStatus;
import com.premisave.wallet.service.PaymentService;
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
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/deduct")
    public ResponseEntity<ApiResponse<PaymentResponse>> deduct(
            @Valid @RequestBody PaymentInitiateRequest request,
            Authentication auth,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        PaymentResponse response = paymentService.deductFromWallet(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Payment processed", response));
    }

    /**
     * Every payment for the authenticated user, newest first. All four
     * query params are optional — omitting all of them returns the same
     * full history as before this filtering was added.
     * GET /payments/history?status=SUCCESS&service=AD_SUBSCRIPTION&fromDate=2026-08-01&toDate=2026-08-18
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<PaymentRecordResponse>>> getHistory(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Authentication auth, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        List<PaymentRecordResponse> history = paymentService.getPaymentHistory(userId, status, service, fromDate, toDate);
        return ResponseEntity.ok(ApiResponse.success("Payment history retrieved", history));
    }
}