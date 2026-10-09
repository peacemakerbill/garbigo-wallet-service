package com.premisave.wallet.service;

import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementRecordResponse;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.ResourceNotFoundException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DisbursementRepository;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Disbursement dispatcher — the shared "can this wallet disburse right
 * now" orchestration (idempotency, wallet lookup, frozen check, balance
 * check, currency normalization), then delegates to the provider-specific
 * service that actually knows how to talk to that provider. Split out of
 * a single large file into MpesaDisbursementService,
 * StripeDisbursementService, PaypalDisbursementService,
 * FlutterwaveDisbursementService, and NowPaymentsDisbursementService —
 * mirroring DepositService's own split at the deposit side, and
 * MpesaService/StripeService/PaypalService/FlutterwaveService/
 * NowPaymentsService's split at the API-integration layer one level down.
 *
 * MPESA and FLUTTERWAVE are self-contained early returns — each builds
 * its own Disbursement record and decides its own PENDING/FAILED outcome
 * internally, since their destination shapes don't fit the generic
 * single-string pattern (M-Pesa resolves a verified phone number from the
 * wallet itself; Flutterwave needs two fields, bank+account, not one).
 * STRIPE/PAYPAL/NOWPAYMENTS share IDENTICAL PENDING-until-webhook status
 * handling, so that logic stays centralized here via the ProviderResult
 * pattern rather than being duplicated three times over — each of those
 * three services' disburseX method returns a ProviderResult, and this
 * class owns turning that into the actual Disbursement record and
 * response.
 *
 * PaymentCallbackController now calls each provider-specific service's
 * completeXDisbursement directly for webhook reconciliation — this class
 * no longer owns any of those methods.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisbursementService {

    private final WalletRepository walletRepository;
    private final DisbursementRepository disbursementRepository;
    private final ExchangeRateService exchangeRateService;
    private final MongoTemplate mongoTemplate;
    private final IdempotencyService idempotencyService;
    private final FxRateService fxRateService;
    private final MpesaDisbursementService mpesaDisbursementService;
    private final StripeDisbursementService stripeDisbursementService;
    private final PaypalDisbursementService paypalDisbursementService;
    private final FlutterwaveDisbursementService flutterwaveDisbursementService;
    private final NowPaymentsDisbursementService nowPaymentsDisbursementService;
    private final CommissionService commissionService;
    private final DisbursementTransactionRecorder disbursementTransactionRecorder;

    // ─── User-facing disbursement (phone / PayPal / Stripe / Flutterwave / NOWPayments) ───

    /**
     * NOTE ON BALANCE TIMING: the wallet is NOT debited here anymore. It's
     * only debited once the disbursement is CONFIRMED — by M-Pesa's
     * ResultURL callback, PayPal's Payouts webhook, Flutterwave's
     * transfer.disburse webhook, Stripe's payout.paid Connect webhook, or
     * NOWPayments' payout IPN webhook — every provider now resolves
     * asynchronously (see each provider service's completeXDisbursement).
     * Previously the wallet was debited up front and refunded on failure;
     * that meant a customer's balance was reduced for money that hadn't
     * actually left yet, and a PENDING disbursement stuck for hours (e.g.
     * during the earlier callback URL misconfiguration) held their funds
     * hostage the whole time even though the provider had already
     * processed the payout successfully.
     *
     * Trade-off to be aware of: since nothing is held/reserved at
     * initiation, two disbursement requests submitted in quick succession
     * could both pass the balance check below and later both succeed,
     * overdrawing the wallet. There's no reservation/hold mechanism here —
     * add one if that scenario becomes a real risk for you.
     */
    @Transactional
    public DisbursementResponse processDisbursement(String userId, DisbursementRequest request) {
        // Resolved BEFORE the idempotency check below — previously the
        // check ran on request.getReference() directly while the
        // UUID-fallback (originally further down) only applied afterward,
        // meaning an omitted reference reached checkIdempotency as null
        // instead of ever getting the fallback that's specifically meant
        // to protect this check.
        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();
        idempotencyService.checkIdempotency(reference);

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) throw new WalletFrozenException("Wallet is frozen");

        String provider = request.getProvider() != null ? request.getProvider().toUpperCase() : "MPESA";

        // MUST run before the balance check below. Mutates request's own
        // amount field in place (Lombok @Data setter) rather than
        // threading a separately-converted value through every one of
        // this method's ~10 existing request.getAmount() call sites (the
        // balance check right below, disbursement.setAmount further down,
        // a synchronous debit for some providers, etc.) — every one of
        // those already correctly and uniformly reads request.getAmount(),
        // so mutating it once here is the minimal, safe fix rather than a
        // larger rewrite.
        //
        // Defaults to KES (no conversion) if unset — DELIBERATELY the
        // OPPOSITE default from DepositRequest.nowPaymentsPriceCurrency
        // (which defaults to USD). Every existing caller of THIS endpoint,
        // across all five providers, has always assumed request.getAmount()
        // is KES; flipping the default here would silently reinterpret
        // every existing NOWPAYMENTS withdrawal request already in flight
        // or hardcoded in a frontend. Only converts when a caller
        // EXPLICITLY opts in with a non-KES value.
        if ("NOWPAYMENTS".equals(provider) && request.getNowPaymentsPriceCurrency() != null
                && !request.getNowPaymentsPriceCurrency().isBlank()
                && !"kes".equalsIgnoreCase(request.getNowPaymentsPriceCurrency())) {
            BigDecimal rateToKes = fxRateService.getRate(request.getNowPaymentsPriceCurrency().toUpperCase(), "KES");
            BigDecimal kesAmount = request.getAmount().multiply(rateToKes).setScale(2, java.math.RoundingMode.HALF_UP);
            log.info("NOWPayments withdrawal priced: requested={} {} kesEquivalent={}",
                    request.getAmount(), request.getNowPaymentsPriceCurrency().toUpperCase(), kesAmount);
            request.setAmount(kesAmount);
        }


        if ("MPESA".equals(provider) && request.getCurrency() != null && !request.getCurrency().isBlank()) {
            String requestedCurrency = request.getCurrency().toUpperCase();
            if ("USD".equals(requestedCurrency)) {
                BigDecimal rateToKes = exchangeRateService.getRate("USD", "KES");
                BigDecimal kesAmount = request.getAmount().multiply(rateToKes).setScale(2, java.math.RoundingMode.HALF_UP);
                log.info("M-Pesa disbursement priced: requested={} USD kesEquivalent={}", request.getAmount(), kesAmount);
                request.setAmount(kesAmount);
            } else if (!"KES".equals(requestedCurrency)) {
                throw new IllegalArgumentException("M-Pesa disbursements must be in KES or USD");
            }
        }

        // Computed ONCE, centrally, on the final (already KES-normalized)
        // amount — every provider path below (both the two self-contained
        // early returns and the shared ProviderResult switch) receives
        // this same commission value, so it's derived exactly once rather
        // than redundantly recomputed per provider. ADDED ON TOP,
        // confirmed explicitly — see CommissionService's javadoc: the
        // external provider/recipient still receives exactly
        // request.getAmount(), unaffected; only the wallet debit grows.
        BigDecimal commission = commissionService.calculateGatewayCommission(request.getAmount());
        BigDecimal totalDebit = request.getAmount().add(commission);

        if (wallet.getBalance().compareTo(totalDebit) < 0)
            throw new InsufficientFundsException("Insufficient funds for disbursement");

        // MPESA and Flutterwave are self-contained early returns — see
        // class javadoc for why.
        if ("MPESA".equals(provider)) {
            return mpesaDisbursementService.disburseMpesa(userId, wallet, request, commission);
        }

        if ("FLUTTERWAVE".equals(provider)) {
            return flutterwaveDisbursementService.processFlutterwaveDisbursement(userId, wallet, request, commission);
        }

        String destination;
        if ("PAYPAL".equals(provider)) {
            if (wallet.getPaypalEmail() == null || wallet.getPaypalEmail().isBlank()) {
                throw new IllegalArgumentException(
                        "No PayPal email is set on your wallet — add one before requesting a PayPal disbursement.");
            }
            destination = wallet.getPaypalEmail();
        } else if ("STRIPE".equals(provider)) {
            // Resolved authoritatively from the wallet's linked Connect
            // account — never taken from request.getDestination() — same
            // reasoning as M-Pesa/PayPal above: eliminates typo/mistargeted
            // payout risk, and the user only ever gets money sent to a bank
            // account Stripe itself verified during onboarding.
            if (wallet.getStripeConnectedAccountId() == null || wallet.getStripeConnectedAccountId().isBlank()) {
                throw new IllegalArgumentException(
                        "No Stripe bank account is linked to your wallet — link one before requesting a Stripe withdrawal.");
            }
            if (!wallet.isStripePayoutsEnabled()) {
                throw new IllegalArgumentException(
                        "Your linked Stripe account hasn't finished verification yet — payouts aren't enabled on it.");
            }
            destination = wallet.getStripeConnectedAccountId();
        } else if ("NOWPAYMENTS".equals(provider)) {
            // Unlike MPESA/PAYPAL/STRIPE above, there's no saved/verified
            // crypto address on the wallet to resolve automatically — the
            // caller supplies both the address and currency per request,
            // same as PayPal's email field, just two fields instead of one
            // since a crypto destination isn't identified by currency alone.
            if (request.getDestination() == null || request.getDestination().isBlank()) {
                throw new IllegalArgumentException("destination (crypto wallet address) is required for NOWPAYMENTS disbursements");
            }
            if (request.getNowPaymentsCurrency() == null || request.getNowPaymentsCurrency().isBlank()) {
                throw new IllegalArgumentException("nowPaymentsCurrency is required for NOWPAYMENTS disbursements");
            }
            destination = request.getDestination();
        } else {
            if (request.getDestination() == null || request.getDestination().isBlank()) {
                throw new IllegalArgumentException("destination is required for " + provider + " disbursements");
            }
            destination = request.getDestination();
        }

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(userId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(request.getAmount());
        disbursement.setTotalDebited(totalDebit);
        // These three providers are already USD-native — totalDebitedUsd
        // simply equals totalDebit, no real conversion needed. Still set
        // explicitly (not left null) so every completion/approval path
        // can read this field the same way regardless of provider,
        // rather than needing provider-specific branching to know
        // whether conversion applies. See Disbursement.totalDebitedUsd
        // javadoc.
        disbursement.setTotalDebitedUsd(totalDebit);
        disbursement.setCommissionRate(commissionService.getGatewayRate());
        disbursement.setDestination(destination);
        disbursement.setProvider(provider);
        disbursement.setReference(reference);
        disbursement.setStatus(DisbursementStatus.PENDING);
        // Fixed: this branch ONLY ever reaches STRIPE/PAYPAL/NOWPAYMENTS
        // (MPESA/FLUTTERWAVE are separate early returns earlier in this
        // method) — all three are confirmed USD-native, with no
        // conversion step in their own completeXDisbursement methods, so
        // "USD" is the real, correct value here, not a hardcoded guess.
        disbursement.setCurrency("USD");

        ProviderResult result = switch (provider) {
            case "STRIPE" -> stripeDisbursementService.disburseStripe(request, destination, reference);
            case "PAYPAL" -> paypalDisbursementService.disbursePaypal(request, destination);
            case "NOWPAYMENTS" -> nowPaymentsDisbursementService.disburseNowPayments(request, destination, reference);
            default -> new ProviderResult(false, "Unsupported provider: " + provider, null);
        };

        disbursement.setChannel(provider + "_PAYOUT");

        if (result.success()) {
            disbursement.setProviderReference(result.providerRef());

            // PayPal, Stripe, and NOWPayments all resolve asynchronously —
            // NOWPayments additionally requires a separate verify step
            // before it even starts processing (see
            // NowPaymentsDisbursementService.verifyNowPaymentsDisbursement)
            // — same PENDING-until-webhook treatment either way, since the
            // wallet can't safely be debited until an external
            // confirmation arrives.
            if ("PAYPAL".equals(provider) || "STRIPE".equals(provider) || "NOWPAYMENTS".equals(provider)) {
                disbursement.setStatus(DisbursementStatus.PENDING);
                disbursementRepository.save(disbursement);
                String providerLabel = switch (provider) {
                    case "STRIPE" -> "Stripe";
                    case "NOWPAYMENTS" -> "NOWPayments";
                    default -> "PayPal";
                };
                String message = "NOWPAYMENTS".equals(provider)
                        ? "Disbursement created with NOWPayments — verify it with your 2FA code "
                                + "(POST /disbursements/nowpayments/" + disbursement.getId() + "/verify) "
                                + "within 1 hour, or it will be automatically rejected."
                        : "Disbursement queued with " + providerLabel + " — your wallet will be debited once "
                                + providerLabel + " confirms the payout.";
                return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), message);
            }

            // No synchronous-success provider remains in this switch — kept
            // as a safety net in case a future provider is added here that
            // DOES resolve synchronously (MPESA/FLUTTERWAVE are handled in
            // their own early-return branches above, in their own
            // dedicated services). Uses totalDebit for consistency with
            // every other debit path, though this branch is genuinely
            // unreachable today — no commission is recorded here since
            // nothing currently reaches it to test that path either.
            wallet.setBalance(wallet.getBalance().subtract(totalDebit));
            walletRepository.save(wallet);

            disbursement.setStatus(DisbursementStatus.SUCCESS);
        } else {
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(result.message());
            log.warn("Disbursement failed for userId={}. Reason: {}", userId, result.message());
        }

        disbursementRepository.save(disbursement);
        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.message());
    }

    // ─── User-facing B2Pochi passthrough ─────────────────────────────────────
    // Kept here so WalletController/DisbursementController's existing call
    // sites don't need to change — delegates straight to MpesaDisbursementService.

    public DisbursementResponse processB2PochiPayment(String initiatedByUserId,
                                                        com.premisave.wallet.dto.B2PochiRequest request) {
        return mpesaDisbursementService.processB2PochiPayment(initiatedByUserId, request);
    }

    // ─── Admin/finance-initiated passthroughs ────────────────────────────────
    // Same reasoning — never touch a customer wallet, but kept reachable
    // from DisbursementService so existing controller call sites don't need
    // to change.

    public DisbursementResponse processB2BPayment(String initiatedByUserId,
                                                    com.premisave.wallet.dto.MpesaB2BRequest request) {
        return mpesaDisbursementService.processB2BPayment(initiatedByUserId, request);
    }

    // ─── NOWPayments 2FA verification passthrough ────────────────────────────
    // Same reasoning as the passthroughs above — DisbursementController
    // calls this on DisbursementService directly, so kept reachable here
    // rather than requiring the controller to inject
    // NowPaymentsDisbursementService separately.

    public void verifyNowPaymentsDisbursement(String disbursementId, String verificationCode, String callerUserId) {
        nowPaymentsDisbursementService.verifyNowPaymentsDisbursement(disbursementId, verificationCode, callerUserId);
    }

    // ─── Stuck-disbursement sweeper ──────────────────────────────────────────
    // Generic across all providers — stays here rather than being
    // duplicated five times, since it only reads DisbursementRepository
    // and doesn't touch any provider-specific logic.

    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void flagStuckDisbursements() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        List<Disbursement> stuck = disbursementRepository.findByStatusAndCreatedAtBefore(
                DisbursementStatus.PENDING, cutoff);

        if (!stuck.isEmpty()) {
            log.warn("{} disbursement(s) stuck in PENDING beyond 30 minutes — needs manual reconciliation: {}",
                    stuck.size(), stuck.stream().map(Disbursement::getId).toList());
        }
    }

    // ─── Admin manual resolution of a stuck disbursement ────────────────────
    // Both only valid on a PENDING disbursement — approving/rejecting an
    // already-resolved one (SUCCESS or FAILED) risks either double-debiting
    // the wallet or incorrectly flipping a genuine outcome, so both reject
    // outright rather than silently allowing it. IllegalArgumentException
    // used for the guard, matching the exception type already used
    // pervasively throughout processDisbursement above for user-facing
    // validation failures in this same file.

    /**
     * Manually resolves a disbursement stuck in PENDING as SUCCESS — for
     * when an admin has independently confirmed via the provider's own
     * dashboard/portal that the payout genuinely went through, but the
     * webhook that would normally trigger this automatically never
     * arrived (a missed callback, not a built-in "awaiting admin sign-off"
     * step — every provider resolves via webhook with no approval gate in
     * the normal flow).
     *
     * Branches on whether walletId is present — NOT just a null-safety
     * guard, but the actual signal for which of two genuinely different
     * kinds of disbursement this is:
     *  - walletId present: a real customer withdrawal (B2C, Stripe/
     *    PayPal/Flutterwave/NOWPayments payouts). Debits that wallet HERE
     *    for the first time — see processDisbursement's own javadoc:
     *    every provider debits the wallet ONLY on confirmed callback,
     *    never at initiation, so a PENDING disbursement hasn't touched
     *    the wallet at all yet. Uses totalDebited (falling back to
     *    amount for legacy records), matching what every provider's own
     *    automatic completeXDisbursement already uses.
     *  - walletId absent: a company-initiated disbursement (B2B, likely
     *    B2C Account Top Up too) — money moving directly out of
     *    Premisave's OWN M-Pesa shortcode, never a customer wallet in the
     *    first place. Confirmed from a real record: userId was
     *    "admin@premisave.com", no walletId anywhere. Approving this
     *    records a NEGATIVE CompanyLedgerEntry (a real loss/expense, per
     *    that entity's own signed-amount convention) via
     *    CommissionService.recordCommission — reused for its actual
     *    generic behavior (build + save a CompanyLedgerEntry) despite the
     *    method's commission-focused name; CompanyLedgerEntry's own
     *    javadoc is explicit that it's "deliberately NOT commission-only."
     *    rate/grossAmount passed null, matching how direct revenue
     *    entries (no percentage applied) already do the same.
     */
    @Transactional
    public DisbursementResponse adminApproveDisbursement(String disbursementId, String approvedBy) {
        if (disbursementId == null || disbursementId.isBlank()) {
            throw new IllegalArgumentException("disbursementId is required");
        }

        Disbursement disbursement = disbursementRepository.findById(disbursementId)
                .orElseThrow(() -> new ResourceNotFoundException("Disbursement not found: " + disbursementId));

        if (disbursement.getStatus() != DisbursementStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Only a PENDING disbursement can be approved — this disbursement is already " + disbursement.getStatus());
        }

        BigDecimal debitAmount;
        if (disbursement.getTotalDebitedUsd() != null) {
            debitAmount = disbursement.getTotalDebitedUsd();
        } else {
            BigDecimal nativeAmount = disbursement.getTotalDebited() != null
                    ? disbursement.getTotalDebited() : disbursement.getAmount();
            String nativeCurrency = disbursement.getCurrency() != null ? disbursement.getCurrency() : "USD";
            debitAmount = "USD".equals(nativeCurrency)
                    ? nativeAmount
                    : nativeAmount.multiply(exchangeRateService.getRate(nativeCurrency, "USD"))
                            .setScale(2, RoundingMode.HALF_UP);
        }

        if (disbursement.getWalletId() == null || disbursement.getWalletId().isBlank()) {
            commissionService.recordCommission("COMPANY_DISBURSEMENT", debitAmount.negate(), null, null,
                    "DISBURSEMENT", disbursement.getId(), disbursement.getReference(), disbursement.getUserId(),
                    "Company-initiated " + disbursement.getProvider() + " " + disbursement.getChannel()
                            + " disbursement to " + disbursement.getDestination()
                            + " — manually approved by admin " + approvedBy,
                    Currency.USD);

            disbursement.setStatus(DisbursementStatus.SUCCESS);
            disbursementRepository.save(disbursement);

            log.info("Company disbursement {} manually approved by admin={} — recorded {} as a company expense "
                    + "on the ledger (no customer wallet involved)", disbursementId, approvedBy, debitAmount);

            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    "Disbursement manually approved — recorded as a company expense (no customer wallet involved)");
        }

        Wallet wallet = walletRepository.findById(disbursement.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + disbursement.getWalletId()));

        wallet.setBalance(wallet.getBalance().subtract(debitAmount));
        walletRepository.save(wallet);

        // Negative — a disbursement is always money leaving the wallet,
        // matching the established sign convention used for every other
        // debit-effect Transaction row in this codebase (Reversal's own
        // refund debit, Transfer's debit side) — recorded as negative,
        // credit-side effects as positive. Not independently confirmed
        // against a real provider-specific completeXDisbursement method's
        // own call to this same recorder (none was available when this
        // was written) — worth checking that one matches this convention
        // too, rather than assuming this is definitely consistent with it.
        disbursementTransactionRecorder.record(disbursement.getUserId(), wallet.getId(), debitAmount.negate(),
                disbursement, disbursement.getReference());

        disbursement.setStatus(DisbursementStatus.SUCCESS);
        disbursementRepository.save(disbursement);

        log.info("Disbursement {} manually approved by admin={} — wallet {} debited {}",
                disbursementId, approvedBy, wallet.getId(), debitAmount);

        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                "Disbursement manually approved");
    }

    /**
     * Manually resolves a disbursement stuck in PENDING as FAILED. No
     * wallet refund is needed or performed — see
     * adminApproveDisbursement's javadoc above: a PENDING disbursement
     * was never debited in the first place, so there's nothing to
     * reverse.
     */
    @Transactional
    public DisbursementResponse adminRejectDisbursement(String disbursementId, String reason, String rejectedBy) {
        if (disbursementId == null || disbursementId.isBlank()) {
            throw new IllegalArgumentException("disbursementId is required");
        }

        Disbursement disbursement = disbursementRepository.findById(disbursementId)
                .orElseThrow(() -> new ResourceNotFoundException("Disbursement not found: " + disbursementId));

        if (disbursement.getStatus() != DisbursementStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Only a PENDING disbursement can be rejected — this disbursement is already " + disbursement.getStatus());
        }

        disbursement.setStatus(DisbursementStatus.FAILED);
        disbursement.setFailureReason("Rejected by admin (" + rejectedBy + "): " + reason);
        disbursementRepository.save(disbursement);

        log.info("Disbursement {} manually rejected by admin={} reason={}", disbursementId, rejectedBy, reason);

        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                "Disbursement rejected: " + reason);
    }

    // ─── User-facing history ──────────────────────────────────────────────────

    /** GET /disbursements/history — every disbursement for this user, across all five providers, newest first. */
    public List<DisbursementRecordResponse> getDisbursementHistory(String userId) {
        return getDisbursementHistory(userId, null, null, null, null);
    }

    /**
     * Filtered version — status/provider/date-range all optional. Same
     * dynamic-Criteria approach as DepositService.getDepositHistory, same
     * reasoning: with every filter left null, produces the identical
     * result set the unfiltered overload above always has.
     */
    public List<DisbursementRecordResponse> getDisbursementHistory(String userId, DisbursementStatus status,
                                                                     String provider, LocalDate fromDate, LocalDate toDate) {
        Criteria criteria = Criteria.where("userId").is(userId);
        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        if (provider != null && !provider.isBlank()) {
            criteria = criteria.and("provider").is(provider.toUpperCase());
        }
        criteria = DateRangeCriteriaUtil.applyDateRange(criteria, "createdAt", fromDate, toDate);

        Query query = new Query(criteria).with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return mongoTemplate.find(query, Disbursement.class).stream()
                .map(DisbursementService::toRecordResponse)
                .toList();
    }

    /** Admin-only: every disbursement across every user, paginated — see AdminFinanceController. */
    public Page<DisbursementRecordResponse> getAllDisbursements(Pageable pageable) {
        return getAllDisbursements(null, null, null, null, null, pageable);
    }

    /**
     * Filtered version — userId/status/provider/date-range all optional.
     * Same dynamic-Criteria approach as getDisbursementHistory above, but
     * admin-scoped (no forced userId restriction — starts from an empty
     * Criteria(), same pattern already proven working in
     * AdminReportService.getDailyReport) and genuinely paginated (a real
     * Page<T> with accurate total count via a separate mongoTemplate.count
     * call, not just this page's own content size) rather than a flat List.
     * With every filter left null, produces the identical result set the
     * unfiltered overload above always has.
     */
    public Page<DisbursementRecordResponse> getAllDisbursements(String userId, DisbursementStatus status,
                                                                  String provider, LocalDate fromDate, LocalDate toDate,
                                                                  Pageable pageable) {
        Criteria criteria = new Criteria();
        if (userId != null && !userId.isBlank()) {
            criteria = criteria.and("userId").is(userId);
        }
        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        if (provider != null && !provider.isBlank()) {
            criteria = criteria.and("provider").is(provider.toUpperCase());
        }
        criteria = DateRangeCriteriaUtil.applyDateRange(criteria, "createdAt", fromDate, toDate);

        long total = mongoTemplate.count(new Query(criteria), Disbursement.class);
        Query pagedQuery = new Query(criteria).with(pageable);
        List<DisbursementRecordResponse> content = mongoTemplate.find(pagedQuery, Disbursement.class).stream()
                .map(DisbursementService::toRecordResponse)
                .toList();

        return new org.springframework.data.domain.PageImpl<>(content, pageable, total);
    }

    private static DisbursementRecordResponse toRecordResponse(Disbursement d) {
        DisbursementRecordResponse r = new DisbursementRecordResponse();
        r.setId(d.getId());
        r.setUserId(d.getUserId());
        r.setAmount(d.getAmount());
        r.setTotalDebited(d.getTotalDebited());
        r.setCommissionRate(d.getCommissionRate());
        r.setCurrency(d.getCurrency());
        r.setNativeAmount(d.getNativeAmount());
        r.setNativeCurrency(d.getNativeCurrency());
        r.setDestination(d.getDestination());
        r.setProvider(d.getProvider());
        r.setChannel(d.getChannel());
        r.setStatus(d.getStatus());
        r.setReference(d.getReference());
        r.setProviderReference(d.getProviderReference());
        r.setFailureReason(d.getFailureReason());
        r.setCreatedAt(d.getCreatedAt());
        return r;
    }
}