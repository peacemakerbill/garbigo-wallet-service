package com.premisave.wallet.service;

import com.premisave.wallet.dto.PendingReconciliationItem;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.MpesaOperation;
import com.premisave.wallet.entity.Payment;
import com.premisave.wallet.entity.Transfer;
import com.premisave.wallet.enums.DepositStatus;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.enums.MpesaOperationType;
import com.premisave.wallet.enums.PaymentStatus;
import com.premisave.wallet.enums.TransferStatus;
import com.premisave.wallet.repository.DepositRepository;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.MpesaOperationRepository;
import com.premisave.wallet.repository.PaymentRepository;
import com.premisave.wallet.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-entity "what's currently pending, across the whole system" view —
 * a genuinely new, dedicated concern rather than something that
 * belonged to any one existing service, since no single service owns
 * all five repositories this needs. Pure read — no resolution logic
 * lives here at all, only discovery.
 *
 * Uses findAll() + Java-side filtering uniformly across all five
 * repositories rather than assuming each one has its own dedicated
 * findByStatus(...) method — some do (Disbursement's
 * findByStatusAndCreatedAtBefore, used by its own sweeper), but rather
 * than assume the others also do and risk a compile failure, this stays
 * uniformly safe. Admin-only, low-frequency dashboard query, not a hot
 * path — the efficiency tradeoff is deliberate and acceptable here.
 *
 * resolvableViaApi is true only where a real, safe, already-built
 * resolution endpoint exists right now: Disbursement (built earlier),
 * Deposit (built alongside this), and M-Pesa Reversal specifically
 * (built alongside this, reusing MpesaOperationsService's own
 * applyReversalToWallet logic). Transfer, Payment, and M-Pesa's
 * non-Reversal operation types (Account Balance, Transaction Status —
 * pure queries with no wallet-affecting outcome, nothing to approve)
 * are listed for visibility but explicitly flagged as not yet
 * resolvable through this API.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final DepositRepository depositRepository;
    private final DisbursementRepository disbursementRepository;
    private final TransferRepository transferRepository;
    private final PaymentRepository paymentRepository;
    private final MpesaOperationRepository mpesaOperationRepository;

    public List<PendingReconciliationItem> getAllPending() {
        List<PendingReconciliationItem> items = new ArrayList<>();

        for (Deposit d : depositRepository.findAll()) {
            if (d.getStatus() == DepositStatus.PENDING) {
                items.add(toItem("DEPOSIT", providerLabel(d.getProvider()) + " Deposit", d.getId(), d.getUserId(),
                        d.getAmount(), d.getProvider(), d.getReference(), d.getCreatedAt(), true,
                        "APPROVE_REJECT_DEPOSIT",
                        "This deposit hasn't been confirmed yet. If the payment actually came through, "
                                + "approve it to credit the customer's wallet. If it didn't, reject it."));
            }
        }

        for (Disbursement d : disbursementRepository.findAll()) {
            if (d.getStatus() == DisbursementStatus.PENDING) {
                items.add(toItem("DISBURSEMENT", providerLabel(d.getProvider()) + " Payout", d.getId(), d.getUserId(),
                        d.getAmount(), d.getProvider(), d.getReference(), d.getCreatedAt(), true,
                        "APPROVE_REJECT_DISBURSEMENT",
                        "This payout hasn't been confirmed yet. If the money actually went out, approve it. "
                                + "If it didn't, reject it."));
            }
        }

        for (Transfer t : transferRepository.findAll()) {
            if (t.getStatus() == TransferStatus.PENDING) {
                items.add(toItem("TRANSFER", "Wallet Transfer", t.getId(), t.getSenderId(), t.getAmount(), null,
                        t.getReference(), t.getCreatedAt(), false, "NEEDS_MANUAL_REVIEW",
                        "This transfer needs to be looked at by the support team before it can be resolved here."));
            }
        }

        for (Payment p : paymentRepository.findAll()) {
            if (p.getStatus() == PaymentStatus.PENDING) {
                items.add(toItem("PAYMENT", "Payment", p.getId(), p.getUserId(), p.getAmount(), null,
                        p.getReference(), p.getCreatedAt(), false, "NEEDS_MANUAL_REVIEW",
                        "This payment needs to be looked at by the support team before it can be resolved here."));
            }
        }

        for (MpesaOperation op : mpesaOperationRepository.findAll()) {
            if (op.getStatus() == DisbursementStatus.PENDING) {
                boolean isReversal = op.getType() == MpesaOperationType.REVERSAL;
                String summary = isReversal ? "M-Pesa Refund"
                        : op.getType() == MpesaOperationType.ACCOUNT_BALANCE ? "M-Pesa Balance Check"
                        : "M-Pesa Status Check";
                String actionCode = isReversal ? "APPROVE_REJECT_REVERSAL" : "CLOSE_OPERATION";
                String message = isReversal
                        ? "This refund hasn't been confirmed yet. If it actually went through, approve it. "
                                + "If it didn't, reject it."
                        : "This was just a check that never got a response back — no money was involved. "
                                + "You can mark it as resolved to clear it from this list.";

                items.add(toItem("MPESA_" + op.getType(), summary, op.getId(), null, null, "MPESA",
                        op.getConversationId(), op.getCreatedAt(), true, actionCode, message));
            }
        }

        items.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
        return items;
    }

    /** "MPESA" -> "M-Pesa", "STRIPE" -> "Stripe", etc. — plain provider names for displaySummary, not the raw uppercase enum-style string. */
    private String providerLabel(String provider) {
        if (provider == null) {
            return "";
        }
        return switch (provider.toUpperCase()) {
            case "MPESA" -> "M-Pesa";
            case "STRIPE" -> "Stripe";
            case "PAYPAL" -> "PayPal";
            case "FLUTTERWAVE" -> "Flutterwave";
            case "NOWPAYMENTS" -> "NOWPayments";
            default -> provider;
        };
    }

    private PendingReconciliationItem toItem(String entityType, String displaySummary, String id, String userId,
                                              BigDecimal amount, String provider, String reference,
                                              LocalDateTime createdAt, boolean resolvableViaApi, String actionCode,
                                              String resolutionMessage) {
        PendingReconciliationItem item = new PendingReconciliationItem();
        item.setEntityType(entityType);
        item.setDisplaySummary(displaySummary);
        item.setId(id);
        item.setUserId(userId);
        item.setAmount(amount);
        item.setProvider(provider);
        item.setReference(reference);
        item.setCreatedAt(createdAt);
        item.setMinutesPending(Duration.between(createdAt, LocalDateTime.now()).toMinutes());
        item.setResolvableViaApi(resolvableViaApi);
        item.setActionCode(actionCode);
        item.setResolutionMessage(resolutionMessage);
        return item;
    }
}