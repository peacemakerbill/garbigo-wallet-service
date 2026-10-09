package com.garbigo.wallet.controller;

import com.garbigo.wallet.dto.ApiResponse;
import com.garbigo.wallet.dto.TransactionResponse;
import com.garbigo.wallet.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> getHistory(Authentication auth) {
        String userId = auth.getName();
        List<TransactionResponse> history = transactionService.getTransactionHistory(userId);
        return ResponseEntity.ok(ApiResponse.success("Transaction history retrieved", history));
    }
}