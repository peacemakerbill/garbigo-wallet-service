package com.premisave.wallet.service;

import com.premisave.wallet.dto.GatewayBalanceSnapshotResponse;
import com.premisave.wallet.dto.ProviderBalanceResponse;
import com.premisave.wallet.entity.GatewayBalanceSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates "what's Premisave's own balance with each payment
 * gateway" across all five providers — the same provider-specific-
 * service + thin-orchestrator pattern used throughout tonight for
 * deposits/disbursements, applied here instead. No new API-integration
 * logic lives here; each provider's own getBalance() (added to
 * StripeService/PaypalService/FlutterwaveService/NowPaymentsService,
 * plus M-Pesa's already-existing async flow reused as-is) does the
 * actual work — this class just calls all five and maps results into
 * one consistent response shape.
 *
 * Persistence itself lives in GatewayBalanceSnapshotService, not here —
 * see that class's javadoc for why (avoiding a circular dependency with
 * MpesaOperationsService, which also needs to save data but can't depend
 * on this class).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderBalanceService {

    private final StripeService stripeService;
    private final PaypalService paypalService;
    private final FlutterwaveService flutterwaveService;
    private final NowPaymentsService nowPaymentsService;
    private final MpesaOperationsService mpesaOperationsService;
    private final GatewayBalanceSnapshotService gatewayBalanceSnapshotService;

    public List<ProviderBalanceResponse> getAllBalances(String checkedBy) {
        return List.of(
                getStripeBalance(checkedBy),
                getPaypalBalance(checkedBy),
                getFlutterwaveBalance(checkedBy),
                getNowPaymentsBalance(checkedBy),
                getMpesaBalance(checkedBy)
        );
    }

    public ProviderBalanceResponse getStripeBalance(String checkedBy) {
        StripeService.BalanceResult result = stripeService.getBalance();
        return toResponse("STRIPE", result.success(), result.message(), mapBalances(
                result.balances().stream().map(b -> Map.entry(b.currency(), b.amounts())).toList()), checkedBy);
    }

    public ProviderBalanceResponse getPaypalBalance(String checkedBy) {
        PaypalService.BalanceResult result = paypalService.getBalance();
        return toResponse("PAYPAL", result.success(), result.message(), mapBalances(
                result.balances().stream().map(b -> Map.entry(b.currency(), b.amounts())).toList()), checkedBy);
    }

    public ProviderBalanceResponse getFlutterwaveBalance(String checkedBy) {
        FlutterwaveService.BalanceResult result = flutterwaveService.getBalance();
        return toResponse("FLUTTERWAVE", result.success(), result.message(), mapBalances(
                result.balances().stream().map(b -> Map.entry(b.currency(), b.amounts())).toList()), checkedBy);
    }

    public ProviderBalanceResponse getNowPaymentsBalance(String checkedBy) {
        NowPaymentsService.BalanceResult result = nowPaymentsService.getBalance();
        return toResponse("NOWPAYMENTS", result.success(), result.message(), mapBalances(
                result.balances().stream().map(b -> Map.entry(b.currency(), b.amounts())).toList()), checkedBy);
    }

    /**
     * M-Pesa has no synchronous "get balance now" call at all — Safaricom's
     * Account Balance API is asynchronous (submit a query, the real
     * balance arrives later via webhook to the existing ResultURL flow,
     * then GET /admin/wallet/mpesa/operations/{conversationId} retrieves
     * it). This method submits that query — reusing the already-built
     * MpesaOperationsService.queryAccountBalance rather than duplicating
     * it — and returns PENDING_ASYNC honestly, rather than pretending a
     * number comes back synchronously the way the other four do.
     *
     * The snapshot saved HERE is only ever the submission acknowledgment
     * — never real balance numbers. The REAL data, once Safaricom's
     * webhook actually delivers it, is saved separately by
     * MpesaOperationsService.saveRealAccountBalanceSnapshot — a
     * completely different code path, since this method returns long
     * before that webhook ever arrives.
     */
    public ProviderBalanceResponse getMpesaBalance(String checkedBy) {
        ProviderBalanceResponse response = new ProviderBalanceResponse();
        response.setProvider("MPESA");
        response.setCheckedBy(checkedBy);
        response.setFetchedAt(LocalDateTime.now());
        response.setBalances(List.of());
        try {
            com.premisave.wallet.dto.MpesaAsyncResponse submission = mpesaOperationsService.queryAccountBalance(checkedBy);
            response.setConversationId(submission.getConversationId());
            response.setOriginatorConversationId(submission.getOriginatorConversationId());

            if (submission.isSuccess()) {
                response.setStatus("PENDING_ASYNC");
                response.setMessage(submission.getMessage() != null
                        ? submission.getMessage() : "Account Balance query submitted to Safaricom");
                response.setPollNote("M-Pesa has no synchronous balance check — Safaricom delivers the real "
                        + "balance asynchronously via webhook. Poll GET /admin/wallet/mpesa/operations/"
                        + submission.getConversationId() + " in roughly 30-60 seconds once Safaricom's callback "
                        + "has had time to land.");
            } else {
                response.setStatus("ERROR");
                response.setMessage(submission.getMessage() != null
                        ? submission.getMessage() : "Safaricom rejected the Account Balance query submission");
            }
        } catch (Exception e) {
            log.error("M-Pesa balance query submission failed", e);
            response.setStatus("ERROR");
            response.setMessage("Failed to submit M-Pesa Account Balance query: " + e.getMessage());
        }
        saveSnapshot(response);
        return response;
    }

    // ─── Saved-balance views (from the database, not a live gateway call) ───

    public List<GatewayBalanceSnapshotResponse> getLatestSavedBalances() {
        return gatewayBalanceSnapshotService.getLatestSavedBalances();
    }

    public Page<GatewayBalanceSnapshotResponse> getSavedBalanceHistory(String provider, Pageable pageable) {
        return gatewayBalanceSnapshotService.getSavedBalanceHistory(provider, pageable);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void saveSnapshot(ProviderBalanceResponse response) {
        List<GatewayBalanceSnapshot.CurrencyBalanceEntry> balances = response.getBalances() == null
                ? List.of()
                : response.getBalances().stream().map(b -> {
                    GatewayBalanceSnapshot.CurrencyBalanceEntry entry = new GatewayBalanceSnapshot.CurrencyBalanceEntry();
                    entry.setCurrency(b.getCurrency());
                    entry.setAmounts(b.getAmounts());
                    return entry;
                }).toList();

        gatewayBalanceSnapshotService.save(response.getProvider(), response.getStatus(), balances,
                response.getMessage(), response.getConversationId(), response.getOriginatorConversationId(),
                response.getCheckedBy());
    }

    private List<ProviderBalanceResponse.CurrencyBalance> mapBalances(
            List<Map.Entry<String, Map<String, BigDecimal>>> entries) {
        return entries.stream().map(e -> {
            ProviderBalanceResponse.CurrencyBalance cb = new ProviderBalanceResponse.CurrencyBalance();
            cb.setCurrency(e.getKey());
            cb.setAmounts(e.getValue());
            return cb;
        }).toList();
    }

    private ProviderBalanceResponse toResponse(String provider, boolean success, String message,
                                                List<ProviderBalanceResponse.CurrencyBalance> balances, String checkedBy) {
        ProviderBalanceResponse response = new ProviderBalanceResponse();
        response.setProvider(provider);
        response.setStatus(success ? "AVAILABLE" : "ERROR");
        response.setBalances(balances);
        response.setMessage(message);
        response.setCheckedBy(checkedBy);
        response.setFetchedAt(LocalDateTime.now());
        saveSnapshot(response);
        return response;
    }
}