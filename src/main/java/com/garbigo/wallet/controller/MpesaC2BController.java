package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.service.MpesaC2BService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin-triggered C2B setup. The actual Safaricom-called callback endpoints
 * (validation/confirmation) live in MpesaC2BCallbackController instead,
 * under paths that deliberately avoid the substring "mpesa" — Safaricom's
 * sandbox rejects any registered CallBackURL/ValidationURL/ConfirmationURL
 * containing that word with "400.002.02 Bad Request - Invalid CallBackURL",
 * even when the URL is otherwise valid and publicly reachable.
 *
 * This controller's own path (/payments/mpesa/c2b/register-urls) is fine to
 * keep as-is — it's never called BY Safaricom, only manually by an admin/ops
 * user, so the "mpesa in URL" restriction (which only applies to URLs you
 * register WITH Safaricom) doesn't apply to it.
 */
@Slf4j
@RestController
@RequestMapping("/payments/mpesa/c2b")
@RequiredArgsConstructor
public class MpesaC2BController {

    private final MpesaC2BService c2bService;

    /**
     * One-time registration — call this endpoint manually (or on startup)
     * to register your validation + confirmation URLs with Safaricom.
     * Restricted to ADMIN/OPERATIONS via SecurityConfig.
     *
     * The top-level ApiResponse message reuses the friendly, human-readable
     * "message" that MpesaC2BService builds (rather than Safaricom's
     * CustomerMessage field, which is always blank for this endpoint — see
     * MpesaC2BService.parseRegisterUrlsResponse for why) so API consumers
     * and UIs get a clear confirmation without having to dig into `data`.
     */
    @PostMapping("/register-urls")
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerUrls() {
        Map<String, Object> result = c2bService.registerUrls();
        String friendlyMessage = (String) result.getOrDefault(
                "message", "C2B URLs registered successfully");
        return ResponseEntity.ok(ApiResponse.success(friendlyMessage, result));
    }

    /**
     * Sandbox-only testing helper — triggers Safaricom's C2B Simulate
     * Transaction endpoint, which pretends a customer paid our Pay Bill.
     * Safaricom then calls our own registered validation URL, and (if
     * accepted) our confirmation URL, exercising the full deposit flow
     * end-to-end without needing a real phone. Hard-blocked outside
     * sandbox — see MpesaC2BService.simulateC2BPayment.
     *
     * Call POST /register-urls first so Safaricom has our callback URLs
     * on file, and make sure billRefNumber matches a wallet's registered
     * mpesaPhoneNumber (not email) — Safaricom will pass it straight to
     * our validation endpoint.
     *
     * Body:
     * {
     *   "amount": "100",                  // optional, defaults to "100"
     *   "msisdn": "254705912645",          // optional, defaults to a sandbox test number
     *   "billRefNumber": "254712345678"    // required — must match an existing wallet's mpesaPhoneNumber
     * }
     */
    @PostMapping("/simulate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> simulate(@RequestBody Map<String, String> body) {
        String amount = body.get("amount");
        String msisdn = body.get("msisdn");
        String billRefNumber = body.get("billRefNumber");

        Map<String, Object> result = c2bService.simulateC2BPayment(amount, msisdn, billRefNumber);
        String friendlyMessage = (String) result.getOrDefault(
                "message", "C2B simulate request submitted");
        return ResponseEntity.ok(ApiResponse.success(friendlyMessage, result));
    }
}