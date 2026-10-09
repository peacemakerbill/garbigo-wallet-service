package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.GatewayBalanceSnapshotResponse;
import com.premisave.wallet.dto.ProviderBalanceResponse;
import com.premisave.wallet.service.ProviderBalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only visibility into Premisave's OWN balance held with each
 * integrated payment gateway (M-Pesa, Stripe, PayPal, Flutterwave,
 * NOWPayments) — not any customer's wallet, the platform's own account
 * with each provider. Lets an admin check all five without logging into
 * five separate dashboards.
 *
 * Same security posture as every other admin controller tonight —
 * ADMIN/FINANCE/OPERATIONS only.
 *
 * Every live check below (all six /admin/gateway-balances/* endpoints)
 * is persisted automatically via ProviderBalanceService — see the
 * /saved endpoints further down for viewing that history without
 * triggering a new live gateway call.
 *
 * M-Pesa is genuinely different from the other four here, not just in
 * this controller's plumbing but in Safaricom's own API design — it has
 * no synchronous balance check at all. Every M-Pesa response from this
 * controller has status=PENDING_ASYNC (or ERROR on submission failure),
 * never AVAILABLE with real numbers — see ProviderBalanceService.
 * getMpesaBalance's javadoc for the full reasoning. Included here anyway
 * for uniformity of the endpoint shape, per explicit request, even
 * though the underlying behavior can't be made uniform — Safaricom's API
 * doesn't support a synchronous balance check no matter how this
 * controller is structured.
 */
@RestController
@RequestMapping("/admin/gateway-balances")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE', 'OPERATIONS')")
public class ProviderBalanceController {

    private final ProviderBalanceService providerBalanceService;

    // ─── Live checks — each one persists automatically ──────────────────────

    /** All five providers in one call. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ProviderBalanceResponse>>> getAllBalances(Authentication auth) {
        List<ProviderBalanceResponse> balances = providerBalanceService.getAllBalances(auth.getName());
        return ResponseEntity.ok(ApiResponse.success("Gateway balances retrieved", balances));
    }

    @GetMapping("/stripe")
    public ResponseEntity<ApiResponse<ProviderBalanceResponse>> getStripeBalance(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Stripe balance retrieved",
                providerBalanceService.getStripeBalance(auth.getName())));
    }

    @GetMapping("/paypal")
    public ResponseEntity<ApiResponse<ProviderBalanceResponse>> getPaypalBalance(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("PayPal balance retrieved",
                providerBalanceService.getPaypalBalance(auth.getName())));
    }

    @GetMapping("/flutterwave")
    public ResponseEntity<ApiResponse<ProviderBalanceResponse>> getFlutterwaveBalance(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Flutterwave balance retrieved",
                providerBalanceService.getFlutterwaveBalance(auth.getName())));
    }

    @GetMapping("/nowpayments")
    public ResponseEntity<ApiResponse<ProviderBalanceResponse>> getNowPaymentsBalance(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("NOWPayments balance retrieved",
                providerBalanceService.getNowPaymentsBalance(auth.getName())));
    }

    /**
     * Submits an M-Pesa Account Balance query — does NOT return an actual
     * balance in this response. See class javadoc and
     * ProviderBalanceService.getMpesaBalance for why.
     */
    @GetMapping("/mpesa")
    public ResponseEntity<ApiResponse<ProviderBalanceResponse>> getMpesaBalance(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("M-Pesa balance query submitted",
                providerBalanceService.getMpesaBalance(auth.getName())));
    }

    // ─── Saved views — read from the database, no live gateway call at all ──

    /**
     * The latest saved check for each provider — "what do we currently
     * believe our balance is, and when did we last actually check" — with
     * no live gateway call involved. A provider never checked yet simply
     * doesn't appear.
     */
    @GetMapping("/saved")
    public ResponseEntity<ApiResponse<List<GatewayBalanceSnapshotResponse>>> getLatestSavedBalances() {
        return ResponseEntity.ok(ApiResponse.success("Saved gateway balances retrieved",
                providerBalanceService.getLatestSavedBalances()));
    }

    /**
     * Full history of every check ever performed for one provider —
     * genuinely useful for spotting a sudden balance drop or confirming a
     * top-up landed, not just "the current number." Wrapped in PagedModel
     * explicitly, not returned as a raw Page<T> — same PageImpl
     * serialization fix confirmed necessary in this app earlier tonight.
     * GET /admin/gateway-balances/saved/stripe?page=0&size=20&sort=createdAt,desc
     */
    @GetMapping("/saved/{provider}")
    public ResponseEntity<ApiResponse<PagedModel<GatewayBalanceSnapshotResponse>>> getSavedBalanceHistory(
            @PathVariable String provider, Pageable pageable) {
        PagedModel<GatewayBalanceSnapshotResponse> body =
                new PagedModel<>(providerBalanceService.getSavedBalanceHistory(provider, pageable));
        return ResponseEntity.ok(ApiResponse.success("Saved balance history retrieved", body));
    }
}