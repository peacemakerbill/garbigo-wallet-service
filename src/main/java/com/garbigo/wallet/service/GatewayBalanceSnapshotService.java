package com.premisave.wallet.service;

import com.premisave.wallet.dto.GatewayBalanceSnapshotResponse;
import com.premisave.wallet.dto.ProviderBalanceResponse;
import com.premisave.wallet.entity.GatewayBalanceSnapshot;
import com.premisave.wallet.repository.GatewayBalanceSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared persistence/query layer for gateway balance snapshots.
 *
 * Extracted into its own service specifically to avoid a circular
 * dependency: ProviderBalanceService already depends on
 * MpesaOperationsService (to trigger the balance query), so
 * MpesaOperationsService can't depend back on ProviderBalanceService to
 * save the REAL balance data that arrives later via M-Pesa's ResultURL
 * webhook — both instead depend on this, which depends on neither.
 *
 * Used two genuinely different ways:
 *  - ProviderBalanceService: saves the synchronous result for Stripe/
 *    PayPal/Flutterwave/NOWPayments, and M-Pesa's submission
 *    acknowledgment (PENDING_ASYNC/ERROR — never real numbers, since
 *    Safaricom's API has no synchronous balance check at all).
 *  - MpesaOperationsService: saves the REAL M-Pesa balance data once it
 *    actually arrives via the webhook — see
 *    MpesaOperationsService.saveRealAccountBalanceSnapshot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayBalanceSnapshotService {

    private static final List<String> KNOWN_PROVIDERS = List.of("STRIPE", "PAYPAL", "FLUTTERWAVE", "NOWPAYMENTS", "MPESA");

    private final GatewayBalanceSnapshotRepository repository;

    /** A failed save is logged but never thrown — the caller's own response (live check or webhook ack) is already correct regardless of whether persistence succeeds. */
    public void save(String provider, String status, List<GatewayBalanceSnapshot.CurrencyBalanceEntry> balances,
                      String message, String conversationId, String originatorConversationId, String checkedBy) {
        try {
            GatewayBalanceSnapshot snapshot = new GatewayBalanceSnapshot();
            snapshot.setProvider(provider);
            snapshot.setStatus(status);
            snapshot.setBalances(balances);
            snapshot.setMessage(message);
            snapshot.setConversationId(conversationId);
            snapshot.setOriginatorConversationId(originatorConversationId);
            snapshot.setCheckedBy(checkedBy);
            repository.save(snapshot);
        } catch (Exception e) {
            log.error("Failed to save gateway balance snapshot for provider={}", provider, e);
        }
    }

    public List<GatewayBalanceSnapshotResponse> getLatestSavedBalances() {
        List<GatewayBalanceSnapshotResponse> result = new ArrayList<>();
        for (String provider : KNOWN_PROVIDERS) {
            repository.findFirstByProviderOrderByCreatedAtDesc(provider)
                    .ifPresent(snapshot -> result.add(toResponse(snapshot)));
        }
        return result;
    }

    public Page<GatewayBalanceSnapshotResponse> getSavedBalanceHistory(String provider, Pageable pageable) {
        return repository.findByProviderOrderByCreatedAtDesc(provider.toUpperCase(), pageable)
                .map(GatewayBalanceSnapshotService::toResponse);
    }

    private static GatewayBalanceSnapshotResponse toResponse(GatewayBalanceSnapshot snapshot) {
        GatewayBalanceSnapshotResponse r = new GatewayBalanceSnapshotResponse();
        r.setProvider(snapshot.getProvider());
        r.setStatus(snapshot.getStatus());
        r.setMessage(snapshot.getMessage());
        r.setConversationId(snapshot.getConversationId());
        r.setOriginatorConversationId(snapshot.getOriginatorConversationId());
        r.setCheckedBy(snapshot.getCheckedBy());
        r.setLastUpdated(snapshot.getCreatedAt());

        List<ProviderBalanceResponse.CurrencyBalance> balances = snapshot.getBalances() == null
                ? List.of()
                : snapshot.getBalances().stream().map(b -> {
                    ProviderBalanceResponse.CurrencyBalance cb = new ProviderBalanceResponse.CurrencyBalance();
                    cb.setCurrency(b.getCurrency());
                    cb.setAmounts(b.getAmounts());
                    return cb;
                }).toList();
        r.setBalances(balances);
        return r;
    }
}