package com.premisave.wallet.controller;

import com.premisave.wallet.dto.MpesaC2BCallbackRequest;
import com.premisave.wallet.service.MpesaC2BService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Handles M-Pesa C2B (Pay Bill) validation + confirmation callbacks from
 * Safaricom Daraja. Both endpoints are PUBLIC — secured by IP allowlist at
 * gateway level (see SecurityConfig / WebConfig exclusions).
 *
 * IMPORTANT: these paths deliberately do NOT contain the substring "mpesa"
 * anywhere. Safaricom's sandbox rejects any ValidationURL/ConfirmationURL
 * containing that word with "400.002.02 Bad Request - Invalid CallBackURL",
 * even when the URL is otherwise valid and publicly reachable (same issue
 * we hit with the STK Push CallBackURL — see PaymentCallbackController).
 * Keep these paths, and the corresponding mpesa.daraja.c2b.validation-url /
 * confirmation-url env values, free of "mpesa".
 *
 * Customer flow:
 *   M-Pesa → Lipa Na M-Pesa → Pay Bill → Shortcode → Account = their M-Pesa
 *   number → Amount → PIN
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MpesaC2BCallbackController {

    private final MpesaC2BService c2bService;

    /**
     * Safaricom calls this BEFORE processing payment to ask: "is this account valid?"
     * We check whether a wallet exists for the M-Pesa number (BillRefNumber).
     * Must respond within 8 seconds with ResultCode 0 (accept) or 1 (reject).
     */
    @PostMapping("/payments/c2b-validation")
    public ResponseEntity<Map<String, String>> validate(@RequestBody MpesaC2BCallbackRequest request) {
        log.info("C2B validation: msisdn={} account={} amount={}",
                request.getMSISDN(), request.getBillRefNumber(), request.getTransAmount());

        boolean valid = c2bService.validateAccount(request.getBillRefNumber());

        if (valid) {
            return ResponseEntity.ok(Map.of(
                    "ResultCode", "0",
                    "ResultDesc", "Accepted"
            ));
        } else {
            log.warn("C2B validation rejected: no wallet for account={}", request.getBillRefNumber());
            return ResponseEntity.ok(Map.of(
                    "ResultCode", "C2B00011",   // Safaricom code for "invalid account"
                    "ResultDesc", "Account not found"
            ));
        }
    }

    /**
     * Safaricom calls this AFTER payment is confirmed on their side.
     * This is where we credit the wallet — always return 200/ResultCode 0.
     */
    @PostMapping("/payments/c2b-confirmation")
    public ResponseEntity<Map<String, String>> confirm(@RequestBody MpesaC2BCallbackRequest request) {
        log.info("C2B confirmation: transId={} msisdn={} account={} amount={}",
                request.getTransID(), request.getMSISDN(), request.getBillRefNumber(), request.getTransAmount());

        try {
            c2bService.processConfirmation(request);
        } catch (Exception e) {
            // Always ACK to Safaricom — they retry indefinitely on non-200
            log.error("C2B confirmation processing error: transId={}", request.getTransID(), e);
        }

        return ResponseEntity.ok(Map.of(
                "ResultCode", "0",
                "ResultDesc", "Success"
        ));
    }
}