package com.premisave.wallet.service;

import com.premisave.wallet.dto.InternalPaymentRequest;
import com.premisave.wallet.dto.PaymentInitiateRequest;
import com.premisave.wallet.dto.PaymentRecordResponse;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Payment;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.PaymentStatus;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.PaymentRepository;
import com.premisave.wallet.repository.WalletRepository;
import com.premisave.wallet.util.DateRangeCriteriaUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wallet-to-platform payment logic (ad subscriptions, booking fees, etc.)
 * — mirrors TransferService's structure and reasoning exactly, one wallet
 * touched instead of two. No commission concept here — unlike a Transfer
 * or Disbursement, a Payment IS company revenue directly; there's no
 * "cut of someone else's money" to take on top of it.
 *
 * Now uses the dedicated Payment entity (Stage 2) instead of the bare
 * Transaction row this used to create directly — same migration
 * Deposit/Disbursement/Transfer already went through. A validation
 * failure (wallet not found, insufficient funds) still throws the same
 * typed exception as before, with no Payment record created — same
 * "record only once the real operation happens" reasoning as everywhere
 * else in this codebase.
 *
 * Two public entry points, one shared implementation:
 *  - deductFromWallet() — user-initiated, via POST /payments/deduct,
 *    payer resolved from their own JWT.
 *  - payInternal() — another Premisave service calling via
 *    POST /internal/payment. Requires an explicit userId in the request
 *    body, since InternalApiKeyFilter authenticates the CALLING SERVICE,
 *    not an end user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;
    private final MongoTemplate mongoTemplate;
    private final WalletService walletService;
    private final IdempotencyService idempotencyService;
    private final PaymentTransactionRecorder paymentTransactionRecorder;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;
    private final CommissionService commissionService;

    @Transactional
    public PaymentResponse deductFromWallet(String userId, PaymentInitiateRequest request) {
        return executePayment(userId, request.getAmount(), request.getService(), request.getDescription(),
                request.getReference(), "USER");
    }

    @Transactional
    public PaymentResponse payInternal(InternalPaymentRequest request) {
        return executePayment(request.getUserId(), request.getAmount(), request.getService(),
                request.getDescription(), request.getReference(), request.getInitiatedBy());
    }

    private PaymentResponse executePayment(String userId, BigDecimal amount, String service, String description,
                                            String requestedReference, String initiatedBy) {
        // Resolved BEFORE the idempotency check -- previously the check
        // ran on requestedReference directly while this same fallback
        // (originally further down) only applied afterward, meaning an
        // omitted reference reached checkIdempotency as null instead of
        // ever getting the fallback that's specifically meant to protect
        // this check -- the exact gap the comment below already described
        // this fallback as fixing, just not fully.
        String reference = requestedReference != null ? requestedReference : UUID.randomUUID().toString();
        idempotencyService.checkIdempotency(reference);

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        walletService.validateWalletForTransaction(userId, amount);

        // Falls back to a generated UUID if no reference was supplied,
        // same as TransferService — the original version of this method
        // saved a null reference outright when one wasn't given, leaving
        // that specific payment with no idempotency protection at all.

        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);

        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setWalletId(wallet.getId());
        payment.setEmail(wallet.getAccountNumber());
        payment.setAmount(amount);
        payment.setCurrency(Currency.USD);
        payment.setService(service);
        payment.setDescription(description);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setReference(reference);
        payment.setInitiatedBy(initiatedBy);
        // Now actually persisted — Payment.senderName exists, see that
        // field's own javadoc for why this was previously resolved but
        // had nowhere to go.
        String senderName = userNameResolver.resolveNameSafely(payment.getEmail());
        payment.setSenderName(senderName);
        paymentRepository.save(payment);

        paymentTransactionRecorder.record(payment);

        // A Payment IS company revenue directly (see class javadoc), so
        // the full amount is recorded with no rate/grossAmount — both
        // null, matching CompanyLedgerEntry.rateApplied's own javadoc:
        // "the whole amount IS the revenue." type is payment.getService()
        // itself (e.g. "AD_SUBSCRIPTION", "BOOKING_FEE") rather than one
        // generic label, for the same reporting-granularity reason
        // gateway commission is split by provider.
        commissionService.recordCommission(payment.getService(), payment.getAmount(), null, null,
                "PAYMENT", payment.getId(), payment.getReference(), payment.getUserId(),
                "Payment for " + payment.getService()
                        + (payment.getDescription() != null ? ": " + payment.getDescription() : ""),
                payment.getCurrency());

        emailService.sendPaymentConfirmation(payment.getEmail(), amount.toPlainString(),
                payment.getCurrency().name(), service, reference,
                senderName, payment.getEmail(), wallet.getId());

        log.info("Payment deducted successfully: userId={} | amount={} | service={} | ref={} | initiatedBy={}",
                userId, amount, service, reference, initiatedBy);

        return new PaymentResponse(true, payment.getId(), "Payment successful");
    }

    /** GET /payments/history — every payment for this user, newest first. */
    public List<PaymentRecordResponse> getPaymentHistory(String userId) {
        return getPaymentHistory(userId, null, null, null, null);
    }

    /**
     * Filtered version — status/service-category/date-range all optional.
     * Same dynamic-Criteria approach as the other three history methods.
     */
    public List<PaymentRecordResponse> getPaymentHistory(String userId, PaymentStatus status, String service,
                                                           LocalDate fromDate, LocalDate toDate) {
        Criteria criteria = Criteria.where("userId").is(userId);
        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        if (service != null && !service.isBlank()) {
            criteria = criteria.and("service").is(service.toUpperCase());
        }
        criteria = DateRangeCriteriaUtil.applyDateRange(criteria, "createdAt", fromDate, toDate);

        Query query = new Query(criteria).with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return mongoTemplate.find(query, Payment.class).stream()
                .map(PaymentService::toRecordResponse)
                .toList();
    }

    /** Admin-only: every payment across every user, paginated — see AdminFinanceController. */
    public Page<PaymentRecordResponse> getAllPayments(Pageable pageable) {
        return paymentRepository.findAll(pageable).map(PaymentService::toRecordResponse);
    }

    private static PaymentRecordResponse toRecordResponse(Payment p) {
        PaymentRecordResponse r = new PaymentRecordResponse();
        r.setId(p.getId());
        r.setUserId(p.getUserId());
        r.setAmount(p.getAmount());
        r.setCurrency(p.getCurrency());
        r.setService(p.getService());
        r.setDescription(p.getDescription());
        r.setStatus(p.getStatus());
        r.setReference(p.getReference());
        r.setFailureReason(p.getFailureReason());
        r.setInitiatedBy(p.getInitiatedBy());
        r.setEmail(p.getEmail());
        r.setCreatedAt(p.getCreatedAt());
        return r;
    }
}