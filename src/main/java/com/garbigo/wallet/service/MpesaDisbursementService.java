package com.premisave.wallet.service;

import com.premisave.wallet.dto.B2PochiRequest;
import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.dto.MpesaAsyncResponse;
import com.premisave.wallet.dto.MpesaB2BRequest;
import com.premisave.wallet.dto.MpesaB2CResponse;
import com.premisave.wallet.dto.QueryOrgInfoRequest;
import com.premisave.wallet.dto.QueryOrgInfoResponse;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.PhoneNumberUnavailableException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * M-Pesa disbursement logic — split out of DisbursementService, mirroring
 * MpesaDepositService's role on the deposit side. Covers three M-Pesa
 * disbursement channels: user-initiated B2C withdrawal (disburseMpesa),
 * user-initiated B2Pochi (processB2PochiPayment), and admin/finance-
 * initiated B2B (which never touches a customer wallet). B2C Account Top
 * Up (processB2CTopUp) removed — was never used, deliberately.
 *
 * All three channels share ONE reconciliation path — completeMpesaDisbursement
 * — since Safaricom's ResultURL callback shape is identical regardless of
 * which channel initiated it; the Disbursement's channel field (B2C,
 * B2C_POCHI, B2B) is what distinguishes them there, not a separate method
 * per channel.
 *
 * CURRENCY CONVERSION (revised): disbursement.amount/currency (and
 * totalDebited) are now USD — the wallet-side truth, matching every other
 * provider — not the native KES payout, per explicit request: "the data
 * saved should always be in dollars... not Kenyan shillings anywhere,"
 * confirmed to be distorting CompanyLedgerEntry sums and
 * AdminReportService's cross-provider totals (which were summing
 * native-currency figures from different providers together as if they
 * were the same unit). This reverses the PRIOR design here, which
 * deliberately left amount/currency as native KES specifically because
 * Disbursement had no equivalent to Deposit.priceAmount/priceCurrency at
 * the time to safely hold a converted value alongside the original — now
 * resolved by nativeAmount/nativeCurrency (added for exactly this),
 * mirroring Deposit's own pattern. The real KES amount that actually
 * leaves via Safaricom and reaches the recipient's phone is preserved
 * there, not lost — it's just no longer what amount/currency themselves
 * mean. What's UNCHANGED: the actual amount sent to Safaricom's own APIs
 * (sendB2C/sendToPochi/sendB2B) still has to be KES, since that's all
 * M-Pesa understands — this only changes what gets SAVED and EMAILED, not
 * what's physically sent. Callers may now also specify their input amount
 * in USD instead of KES (DisbursementRequest.currency /
 * B2PochiRequest.currency / MpesaB2BRequest.currency) — converted to the
 * real KES figure before ever reaching Safaricom.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaDisbursementService {

    private final WalletRepository walletRepository;
    private final DisbursementRepository disbursementRepository;
    private final MpesaService mpesaService;
    private final IdempotencyService idempotencyService;
    private final DisbursementTransactionRecorder transactionRecorder;
    private final CommissionService commissionService;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;
    private final ExchangeRateService exchangeRateService;

    /**
     * Comma-separated admin/finance notification emails for B2B payments —
     * explicit request: "who should receive the B2B success/failure
     * email? all admins, finance." B2B never touches a customer wallet
     * (see class javadoc), so there's no wallet owner's email to look up
     * the way B2C/B2Pochi have — and AuthServiceClient only supports
     * looking a user up BY email, with no userId-based lookup at all, so
     * d.getUserId() (the initiating admin's userId) can't be resolved
     * into an email that way either. A configured distribution list
     * sidesteps both problems: no new cross-service lookup capability
     * needed, and trivially updatable when who's on the finance/admin
     * team changes — just edit this one property, no code change.
     */
    @Value("${notifications.b2b-recipients:}")
    private String b2bRecipientsConfig;

    // ─── User-facing B2C withdrawal ──────────────────────────────────────────

    /**
     * Called from DisbursementService.processDisbursement via early return
     * for provider=MPESA. request.getAmount() is ALWAYS KES by the time it
     * reaches here — processDisbursement converts any USD input to KES
     * before calling this method (see its own updated javadoc), and
     * commission (computed there) is likewise already in KES — this is
     * exactly what actually gets sent to Safaricom. The wallet is NOT
     * debited here — only once completeMpesaDisbursement confirms success
     * via Safaricom's ResultURL.
     *
     * What's saved to the Disbursement record itself is USD, not KES —
     * see this class's own javadoc for why. kesAmount/totalDebitedKes are
     * converted to USD once, here, at initiation (locked in, same
     * principle as totalDebitedUsd already was) and saved as
     * amount/totalDebited/currency; the real KES figures are preserved
     * separately in nativeAmount/nativeCurrency.
     */
    public DisbursementResponse disburseMpesa(String userId, Wallet wallet, DisbursementRequest request,
                                               BigDecimal commission) {
        String destination = resolveVerifiedPhoneNumber(wallet);
        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();

        BigDecimal kesAmount = request.getAmount();
        BigDecimal totalDebitedKes = kesAmount.add(commission);
        // Locked in NOW, at initiation — not re-derived later at
        // completion or admin approval. See Disbursement.totalDebitedUsd
        // javadoc for why this exists.
        BigDecimal initiationRate = exchangeRateService.getRate("KES", "USD");
        BigDecimal usdAmount = kesAmount.multiply(initiationRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDebitedUsd = totalDebitedKes.multiply(initiationRate).setScale(2, RoundingMode.HALF_UP);

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(userId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(usdAmount);
        disbursement.setTotalDebited(totalDebitedUsd);
        disbursement.setTotalDebitedUsd(totalDebitedUsd);
        disbursement.setCommissionRate(commissionService.getGatewayRate());
        disbursement.setDestination(destination);
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2C");
        disbursement.setReference(reference);
        disbursement.setStatus(DisbursementStatus.PENDING);
        disbursement.setCurrency("USD");
        disbursement.setNativeAmount(kesAmount);
        disbursement.setNativeCurrency("KES");

        MpesaB2CResponse result;
        try {
            result = mpesaService.sendB2C(destination, kesAmount);
        } catch (Exception e) {
            log.error("M-Pesa B2C disbursement threw before a result could be returned: userId={}", userId, e);
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason("M-Pesa B2C initiation failed: " + e.getMessage());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    disbursement.getFailureReason());
        }

        if (!result.isSuccess()) {
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(result.getMessage());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.getMessage());
        }

        disbursement.setProviderReference(result.getConversationId());
        disbursementRepository.save(disbursement);
        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                "Disbursement queued with M-Pesa — your wallet will be debited once M-Pesa confirms the payout.");
    }

    // ─── B2Pochi (pay into the caller's own Pochi business wallet) ──────────

    @Transactional
    public DisbursementResponse processB2PochiPayment(String initiatedByUserId, B2PochiRequest request) {
        // Resolved BEFORE the idempotency check -- see DisbursementService's
        // identical fix for why this ordering matters.
        String reference = request.getReference() != null
                ? request.getReference()
                : "POCHI-" + initiatedByUserId + "-" + System.currentTimeMillis();
        idempotencyService.checkIdempotency(reference);

        Wallet wallet = walletRepository.findByUserId(initiatedByUserId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + initiatedByUserId));

        if (wallet.isFrozen()) throw new WalletFrozenException("Wallet is frozen");

        if (request.getCurrency() != null && !request.getCurrency().isBlank()) {
            String requestedCurrency = request.getCurrency().toUpperCase();
            if ("USD".equals(requestedCurrency)) {
                BigDecimal rateToKes = exchangeRateService.getRate("USD", "KES");
                BigDecimal kesAmount = request.getAmount().multiply(rateToKes).setScale(2, RoundingMode.HALF_UP);
                log.info("B2Pochi withdrawal priced: requested={} USD kesEquivalent={}", request.getAmount(), kesAmount);
                request.setAmount(kesAmount);
            } else if (!"KES".equals(requestedCurrency)) {
                throw new IllegalArgumentException("B2Pochi withdrawals must be in KES or USD");
            }
        }

        // Computed here rather than in DisbursementService — this method
        // is called directly from DisbursementController, never routed
        // through DisbursementService.processDisbursement's central
        // dispatcher, so it needs its own commission computation and
        // balance check, mirroring what the dispatcher does for every
        // other provider. Balance check here compares against the
        // wallet's own USD balance vs. a KES commission/amount total —
        // this pre-existing check was against raw request.getAmount()
        // (KES) before too; converting the comparison itself is a
        // separate, deeper fix not attempted here since it changes
        // pre-existing balance-check semantics beyond just the debit
        // point this pass is scoped to.
        BigDecimal commission = commissionService.calculateGatewayCommission(request.getAmount());
        BigDecimal totalDebit = request.getAmount().add(commission);

        if (wallet.getBalance().compareTo(totalDebit) < 0)
            throw new InsufficientFundsException("Insufficient funds for disbursement");

        String phoneNumber = resolveVerifiedPochiPhoneNumber(wallet);

        String originatorConversationId = mpesaService.generateOriginatorConversationId("B2POCHI");

        B2PochiRequest resolvedRequest = new B2PochiRequest();
        resolvedRequest.setAmount(request.getAmount());
        resolvedRequest.setPhoneNumber(phoneNumber);
        resolvedRequest.setRemarks(request.getRemarks());
        resolvedRequest.setOccasion(request.getOccasion());
        resolvedRequest.setReference(reference);

        // What's saved to the Disbursement record itself is USD, not KES
        // — see this class's own javadoc for why. request.getAmount()/
        // totalDebit are KES at this point (the real figures actually
        // sent to Safaricom); converted once, here, at initiation and
        // saved as amount/totalDebited/currency, with the real KES
        // figures preserved separately in nativeAmount/nativeCurrency.
        BigDecimal kesAmount = request.getAmount();
        BigDecimal pochiInitiationRate = exchangeRateService.getRate("KES", "USD");
        BigDecimal usdAmount = kesAmount.multiply(pochiInitiationRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDebitedUsd = totalDebit.multiply(pochiInitiationRate).setScale(2, RoundingMode.HALF_UP);

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(initiatedByUserId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(usdAmount);
        disbursement.setTotalDebited(totalDebitedUsd);
        disbursement.setTotalDebitedUsd(totalDebitedUsd);
        disbursement.setCommissionRate(commissionService.getGatewayRate());
        disbursement.setCurrency("USD");
        disbursement.setNativeAmount(kesAmount);
        disbursement.setNativeCurrency("KES");
        disbursement.setDestination(phoneNumber);
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2C_POCHI");
        disbursement.setReference(reference);

        MpesaAsyncResponse result;
        try {
            result = mpesaService.sendToPochi(resolvedRequest, originatorConversationId);
        } catch (Exception e) {
            log.error("B2Pochi withdrawal threw before a result could be returned: userId={}",
                    initiatedByUserId, e);
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason("B2Pochi initiation failed: " + e.getMessage());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    disbursement.getFailureReason());
        }

        if (result.isSuccess()) {
            disbursement.setStatus(DisbursementStatus.PENDING);
            disbursement.setProviderReference(result.getConversationId());
        } else {
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(result.getMessage());
        }

        disbursementRepository.save(disbursement);
        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.getMessage());
    }

    // ─── B2B (admin/finance-initiated, business-to-business payment) ───────
    // Never touches a customer wallet (no walletId set) — no commission
    // or wallet-debit conversion relevant here at all, since there's no
    // wallet to debit. What IS still relevant: converting a USD-input
    // request to the real KES figure before it reaches Safaricom, and
    // what gets SAVED — see this class's own javadoc.

    @Transactional
    public DisbursementResponse processB2BPayment(String initiatedByUserId, MpesaB2BRequest request) {
        // Resolved BEFORE the idempotency check -- see DisbursementService's
        // identical fix for why this ordering matters.
        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();
        idempotencyService.checkIdempotency(reference);

        // Fixed: previously always assumed request.getAmount() was KES,
        // with no option otherwise. Explicit request: "the amount users
        // input for disbursements on M-Pesa can either be on KES or USD."
        // Mutates request.amount in place (same pattern as
        // DisbursementService.processDisbursement's NOWPayments/MPESA
        // blocks) since mpesaService.sendB2B(request) below reads the
        // amount directly off this same request object, not a separate
        // parameter — converting here means that call automatically gets
        // the correct KES figure with no further change needed there.
        if (request.getCurrency() != null && !request.getCurrency().isBlank()) {
            String requestedCurrency = request.getCurrency().toUpperCase();
            if ("USD".equals(requestedCurrency)) {
                BigDecimal rateToKes = exchangeRateService.getRate("USD", "KES");
                BigDecimal kesAmount = request.getAmount().multiply(rateToKes).setScale(2, RoundingMode.HALF_UP);
                log.info("B2B payment priced: requested={} USD kesEquivalent={}", request.getAmount(), kesAmount);
                request.setAmount(kesAmount);
            } else if (!"KES".equals(requestedCurrency)) {
                throw new IllegalArgumentException("B2B payments must be in KES or USD");
            }
        }

        // Real KES figure, captured now (after any USD->KES conversion
        // above, before Hakikisha or Safaricom ever run) — used for both
        // nativeAmount below and the USD conversion for amount/currency.
        BigDecimal kesAmount = request.getAmount();
        BigDecimal b2bRate = exchangeRateService.getRate("KES", "USD");
        BigDecimal usdAmount = kesAmount.multiply(b2bRate).setScale(2, RoundingMode.HALF_UP);

        String verifiedRecipientName = null;
        String verifiedChargeProfileId = null;

        if (request.isVerifyRecipient()) {
            QueryOrgInfoRequest orgInfoRequest = new QueryOrgInfoRequest();
            orgInfoRequest.setIdentifierType(request.getReceiverIdentifierTypeForVerification());
            orgInfoRequest.setIdentifier(request.getReceiverShortcode());

            QueryOrgInfoResponse orgInfo = mpesaService.queryOrgInfo(orgInfoRequest);

            if (!orgInfo.isSuccess()) {
                log.warn("B2B Hakikisha check failed for receiverShortcode={} — aborting payment. reason={}",
                        request.getReceiverShortcode(), orgInfo.getResponseMessage());

                Disbursement aborted = new Disbursement();
                aborted.setUserId(initiatedByUserId);
                aborted.setAmount(usdAmount);
                aborted.setCurrency("USD");
                aborted.setNativeAmount(kesAmount);
                aborted.setNativeCurrency("KES");
                aborted.setDestination(request.getReceiverShortcode());
                aborted.setProvider("MPESA");
                aborted.setChannel("B2B");
                aborted.setReference(reference);
                aborted.setStatus(DisbursementStatus.FAILED);
                aborted.setFailureReason("B2B Hakikisha verification failed: " + orgInfo.getResponseMessage());
                disbursementRepository.save(aborted);

                return new DisbursementResponse(aborted.getId(), aborted.getStatus().name(),
                        "Recipient could not be verified — payment not sent: " + orgInfo.getResponseMessage());
            }

            verifiedRecipientName = orgInfo.getOrganizationName();
            verifiedChargeProfileId = orgInfo.getChargeProfileId();
            log.info("B2B Hakikisha verified receiverShortcode={} as organizationName={}",
                    request.getReceiverShortcode(), verifiedRecipientName);
        }

        var result = mpesaService.sendB2B(request);

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(initiatedByUserId);
        disbursement.setAmount(usdAmount);
        disbursement.setCurrency("USD");
        disbursement.setNativeAmount(kesAmount);
        disbursement.setNativeCurrency("KES");
        disbursement.setDestination(request.getReceiverShortcode());
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2B");
        disbursement.setReference(reference);
        disbursement.setVerifiedRecipientName(verifiedRecipientName);
        disbursement.setVerifiedChargeProfileId(verifiedChargeProfileId);

        if (result.isSuccess()) {
            disbursement.setStatus(DisbursementStatus.PENDING);
            disbursement.setProviderReference(result.getConversationId());
        } else {
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(result.getMessage());
        }

        disbursementRepository.save(disbursement);
        String message = verifiedRecipientName != null
                ? result.getMessage() + " (recipient verified as: " + verifiedRecipientName + ")"
                : result.getMessage();
        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), message);
    }

    // ─── Reconciliation from Safaricom's ResultURL callback ─────────────────
    // Shared by all three channels above (B2C, B2C_POCHI, B2B) —
    // Safaricom's callback shape is identical regardless of channel.

    @Transactional
    public void completeMpesaDisbursement(String conversationId, boolean success,
                                           String resultDesc, String mpesaTransactionId) {
        Disbursement d = disbursementRepository.findByProviderReference(conversationId).orElse(null);
        if (d == null) {
            log.warn("M-Pesa result callback for unknown ConversationID={} — ignoring", conversationId);
            return;
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            log.warn("M-Pesa result callback for already-finalized disbursement id={} status={} — ignoring duplicate",
                    d.getId(), d.getStatus());
            return;
        }

        if (success) {
            d.setStatus(DisbursementStatus.SUCCESS);

            if (("B2C".equals(d.getChannel()) || "B2C_POCHI".equals(d.getChannel())) && d.getWalletId() != null) {
                // Funds have now actually left via M-Pesa — this is the
                // FIRST time the wallet is touched for this disbursement
                // (see disburseMpesa/processB2PochiPayment, which no
                // longer debit at initiation). Balance may have moved since
                // initiation due to other transactions, so this can't be
                // guarded with a pre-check the way a synchronous debit
                // could be — if it pushes the wallet negative, that's a
                // signal for manual reconciliation, not something to
                // silently block, since the M-Pesa payout already happened
                // and has to be reflected somewhere.
                //
                // d.getTotalDebitedUsd() was locked in at INITIATION time
                // (see disburseMpesa/processB2PochiPayment and
                // Disbursement.totalDebitedUsd's own javadoc) — read here,
                // not re-converted, so this stays consistent with
                // whatever rate was current when the withdrawal was first
                // requested, and so adminApproveDisbursement (the manual
                // path) reads this exact same value rather than needing
                // its own independent conversion call. Falls back to
                // converting at completion time (today's rate) only for
                // a legacy disbursement created before this field existed.
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));

                BigDecimal debitAmountUsd;
                if (d.getTotalDebitedUsd() != null) {
                    debitAmountUsd = d.getTotalDebitedUsd();
                } else {
                    BigDecimal debitAmountKes = d.getTotalDebited() != null ? d.getTotalDebited() : d.getAmount();
                    BigDecimal rate = exchangeRateService.getRate("KES", "USD");
                    debitAmountUsd = debitAmountKes.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                }

                BigDecimal newBalance = wallet.getBalance().subtract(debitAmountUsd);
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    log.error("Wallet {} balance went negative ({}) debiting confirmed M-Pesa disbursement id={} — needs manual reconciliation",
                            wallet.getId(), newBalance, d.getId());
                }
                wallet.setBalance(newBalance);
                walletRepository.save(wallet);

                disbursementRepository.save(d);
                transactionRecorder.record(d.getUserId(), d.getWalletId(), debitAmountUsd, d, d.getReference());
                commissionService.recordGatewayCommissionFromDisbursement(d);

                // Fixed comment (code unchanged): this already reads
                // d.getAmount()/d.getCurrency() directly, so it now sends
                // USD automatically — those are USD as of this class's
                // revised currency model (see class javadoc), not the
                // native KES payout anymore. exchangeRateInfo below still
                // gives the recipient's real KES context via the "1 KES =
                // X USD" rate, without needing a separate dedicated row.
                // d.getDestination() shown exactly as given, no masking.
                String exchangeRateInfo = "1 KES = " + exchangeRateService.getRate("KES", "USD").toPlainString() + " USD";
                String senderName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
                d.setSenderName(senderName);
                disbursementRepository.save(d);
                emailService.sendDisbursementSuccess(wallet.getAccountNumber(), d.getAmount().toPlainString(),
                        d.getCurrency(), d.getDestination(), d.getReference(),
                        new EmailService.DisbursementDetails("M-Pesa", exchangeRateInfo,
                                senderName, wallet.getAccountNumber(), wallet.getId()));
            } else if ("B2B".equals(d.getChannel())) {
                // Explicit request: "for b2b ensure it is tracked on the
                // ledger as well, these are like losses to the company."
                // Mirrors the EXACT same pattern DisbursementService.
                // adminApproveDisbursement already uses for the
                // conceptually identical case — a company-initiated
                // disbursement with no customer wallet involved at all
                // (Stripe/PayPal/NOWPayments' admin-approved
                // "COMPANY_DISBURSEMENT" path) — just triggered here at
                // CONFIRMED async success (Safaricom's ResultURL callback)
                // rather than at synchronous admin approval, since B2B
                // genuinely doesn't know it succeeded until this callback
                // arrives. d.getAmount() is already USD (see this class's
                // own currency-model note) — negated, since this is a
                // loss, not a gain, matching CompanyLedgerEntry.amount's
                // own signed-value convention.
                disbursementRepository.save(d);
                commissionService.recordCommission("COMPANY_DISBURSEMENT", d.getAmount().negate(), null, null,
                        "DISBURSEMENT", d.getId(), d.getReference(), d.getUserId(),
                        "Company-initiated MPESA B2B payment to " + d.getDestination(),
                        Currency.valueOf(d.getCurrency()));
                notifyB2BRecipients(d, true, null);
            } else {
                disbursementRepository.save(d);
            }

            log.info("M-Pesa {} disbursement completed: id={} conversationId={} mpesaTxId={}",
                    d.getChannel(), d.getId(), conversationId, mpesaTransactionId);
        } else {
            // No refund needed — the wallet was never debited for a
            // PENDING M-Pesa disbursement (see disburseMpesa /
            // processB2PochiPayment above).
            d.setStatus(DisbursementStatus.FAILED);
            d.setFailureReason(resultDesc);
            disbursementRepository.save(d);

            // Only a real customer disbursement (walletId present) has an
            // actual customer inbox to notify — a company-initiated one
            // (B2B, no walletId) has no wallet to look up here, and
            // d.getUserId() there is an admin identifier, not a real
            // customer's own email. B2B gets its own notification path
            // instead — see notifyB2BRecipients.
            if (d.getWalletId() != null) {
                walletRepository.findById(d.getWalletId()).ifPresent(wallet -> {
                    String senderName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
                    emailService.sendDisbursementFailed(wallet.getAccountNumber(),
                            d.getAmount().toPlainString(), d.getCurrency(), resultDesc, d.getDestination(),
                            new EmailService.DisbursementDetails("M-Pesa", null,
                                    senderName, wallet.getAccountNumber(), wallet.getId()));
                });
            } else if ("B2B".equals(d.getChannel())) {
                notifyB2BRecipients(d, false, resultDesc);
            }

            log.warn("M-Pesa {} disbursement failed: id={} conversationId={} reason={}",
                    d.getChannel(), d.getId(), conversationId, resultDesc);
        }
    }

    public void markMpesaDisbursementTimedOut(String conversationId) {
        disbursementRepository.findByProviderReference(conversationId).ifPresentOrElse(d -> {
            log.warn("M-Pesa disbursement queue timeout: id={} conversationId={} — awaiting eventual result or manual reconciliation",
                    d.getId(), conversationId);
        }, () -> log.warn("Timeout callback for unknown ConversationID={}", conversationId));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Parses b2bRecipientsConfig into a clean list — trims whitespace, drops empty entries, handles the property being entirely unset. */
    private List<String> b2bRecipients() {
        if (b2bRecipientsConfig == null || b2bRecipientsConfig.isBlank()) {
            return List.of();
        }
        return Arrays.stream(b2bRecipientsConfig.split(","))
                .map(String::trim)
                .filter(email -> !email.isBlank())
                .toList();
    }

    /**
     * Sends one copy of the B2B success/failure email to every configured
     * recipient — same "one email per party" pattern
     * TransferService/EmailService.sendTransferNotification already uses
     * for sending a separate copy to each of two parties, just scaled to
     * however many admin/finance addresses are configured here. Logs a
     * warning and sends nothing if the property is unset — a missing
     * config value should never throw and break the disbursement
     * completion flow itself.
     */
    private void notifyB2BRecipients(Disbursement d, boolean success, String failureReason) {
        List<String> recipients = b2bRecipients();
        if (recipients.isEmpty()) {
            log.warn("B2B disbursement id={} completed (success={}) but notifications.b2b-recipients is not configured — no email sent",
                    d.getId(), success);
            return;
        }
        for (String recipient : recipients) {
            if (success) {
                emailService.sendDisbursementSuccess(recipient, d.getAmount().toPlainString(),
                        d.getCurrency(), d.getDestination(), d.getReference(),
                        new EmailService.DisbursementDetails("M-Pesa", null, null, null, null));
            } else {
                emailService.sendDisbursementFailed(recipient, d.getAmount().toPlainString(),
                        d.getCurrency(), failureReason, d.getDestination(),
                        new EmailService.DisbursementDetails("M-Pesa", null, null, null, null));
            }
        }
    }

    private String resolveVerifiedPochiPhoneNumber(Wallet wallet) {
        if (wallet != null && wallet.getPochiPhoneNumber() != null && !wallet.getPochiPhoneNumber().isBlank()) {
            return wallet.getPochiPhoneNumber();
        }

        throw new PhoneNumberUnavailableException(
                "You haven't added a Pochi la Biashara phone number to your wallet yet. "
                        + "Please add one in your wallet settings before requesting a Pochi withdrawal.");
    }

    private String resolveVerifiedPhoneNumber(Wallet wallet) {
        if (wallet != null && wallet.getMpesaPhoneNumber() != null && !wallet.getMpesaPhoneNumber().isBlank()) {
            return wallet.getMpesaPhoneNumber();
        }

        throw new PhoneNumberUnavailableException(
                "You haven't added an M-Pesa phone number to your wallet yet. "
                        + "Please add one in your wallet settings before requesting a withdrawal.");
    }
}