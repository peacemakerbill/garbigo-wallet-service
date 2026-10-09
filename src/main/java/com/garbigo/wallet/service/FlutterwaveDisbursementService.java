package com.premisave.wallet.service;

import com.premisave.wallet.config.FlutterwaveConfig;
import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Flutterwave disbursement logic — split out of DisbursementService,
 * mirroring FlutterwaveDepositService's role on the deposit side.
 *
 * CURRENCY CONVERSION: same principle as MpesaDisbursementService —
 * disbursement.amount/currency represent the REAL, NATIVE payout Flutterwave
 * actually sent — destinationCurrency, whatever the request specified
 * (defaulting to KES). currency is a plain String (matching
 * Deposit.priceCurrency's own convention), not the Currency enum — a
 * disbursement's native payout currency can genuinely be anything a
 * gateway supports, which the fixed three-value enum couldn't represent.
 * The wallet is fixed at USD, so conversion is applied ONLY at the point
 * the wallet is actually debited, inside completeFlutterwaveDisbursement's
 * success branch — via ExchangeRateService, keyed off d.getCurrency(),
 * which supports any ISO 4217 pair Frankfurter itself covers, not a
 * fixed list.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlutterwaveDisbursementService {

    private final WalletRepository walletRepository;
    private final DisbursementRepository disbursementRepository;
    private final FlutterwaveService flutterwaveService;
    private final FlutterwaveConfig flutterwaveConfig;
    private final DisbursementTransactionRecorder transactionRecorder;
    private final CommissionService commissionService;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;
    private final ExchangeRateService exchangeRateService;

    /**
     * Called from DisbursementService.processDisbursement via early
     * return for provider=FLUTTERWAVE — self-contained, same shape as
     * MpesaDisbursementService.disburseMpesa, rather than routing through
     * the shared ProviderResult pattern PayPal/Stripe/NOWPayments use.
     * A transfer destination is two fields (account_bank + account_number),
     * not a single "destination" string — same reason M-Pesa gets its own
     * early return too.
     *
     * Same not-debited-until-confirmed pattern as every other provider —
     * Flutterwave transfers resolve asynchronously via the
     * transfer.disburse webhook (see completeFlutterwaveDisbursement),
     * where the actual KES->USD (or whatever d.getCurrency()->USD)
     * conversion is applied.
     *
     * Branches on flutterwaveTransferType (validated below):
     *  - MOBILE_MONEY → FlutterwaveService.initiateTransfer (msisdn/network body)
     *  - BANK         → FlutterwaveService.initiateBankTransfer (bank code/account_number body)
     * These are genuinely different request shapes — routing a bank account
     * number through the mobile-money body (as msisdn) would either fail
     * outright or, worse, silently route to a wrong/unintended mobile
     * wallet if the digits happen to parse as a valid MSISDN.
     *
     * destination_currency comes from the request (defaults to KES).
     * source_currency comes from FlutterwaveConfig.transfer.sourceCurrency
     * — this is your Flutterwave balance's actual currency, which is NOT
     * necessarily the same as destinationCurrency (confirm this against
     * your dashboard balance before relying on it in production).
     */
    public DisbursementResponse processFlutterwaveDisbursement(String userId, Wallet wallet,
                                                                  DisbursementRequest request, BigDecimal commission) {
        if (request.getFlutterwaveAccountBank() == null || request.getFlutterwaveAccountBank().isBlank()) {
            throw new IllegalArgumentException("flutterwaveAccountBank is required for FLUTTERWAVE disbursements");
        }
        if (request.getFlutterwaveAccountNumber() == null || request.getFlutterwaveAccountNumber().isBlank()) {
            throw new IllegalArgumentException("flutterwaveAccountNumber is required for FLUTTERWAVE disbursements");
        }
        String transferType = request.getFlutterwaveTransferType() != null
                ? request.getFlutterwaveTransferType().toUpperCase() : null;
        if (!"BANK".equals(transferType) && !"MOBILE_MONEY".equals(transferType)) {
            throw new IllegalArgumentException(
                    "flutterwaveTransferType must be BANK or MOBILE_MONEY for FLUTTERWAVE disbursements");
        }

        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();

        // For MOBILE_MONEY: flutterwaveAccountBank = network code (e.g. "Mpesa", "MTN"),
        //                    flutterwaveAccountNumber = msisdn (must include country code).
        // For BANK:         flutterwaveAccountBank = bank code,
        //                    flutterwaveAccountNumber = account number.
        String displayDestination = request.getFlutterwaveAccountBank() + "-" + request.getFlutterwaveAccountNumber();

        // Parse beneficiary name into first/last
        String beneficiaryName = request.getFlutterwaveBeneficiaryName();
        String firstName = "";
        String lastName = "";
        if (beneficiaryName != null && !beneficiaryName.isBlank()) {
            String[] parts = beneficiaryName.trim().split("\\s+", 2);
            firstName = parts[0];
            lastName = parts.length > 1 ? parts[1] : "";
        }

        // destination_currency = what the recipient actually receives in
        // — computed here, BEFORE disbursement creation, so the real
        // value can be recorded on it rather than a hardcoded guess.
        String destinationCurrency = request.getCurrency() != null ? request.getCurrency().toUpperCase() : "KES";
        // source_currency = the currency your Flutterwave balance actually holds
        // — see FlutterwaveConfig.Transfer.sourceCurrency javadoc.
        String sourceCurrency = flutterwaveConfig.getTransfer().getSourceCurrency();

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(userId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(request.getAmount());
        BigDecimal totalDebited = request.getAmount().add(commission);
        disbursement.setTotalDebited(totalDebited);
        // Locked in NOW, at initiation — see Disbursement.totalDebitedUsd
        // javadoc. Uses destinationCurrency, already computed above — the
        // real native payout currency, not a hardcoded guess.
        BigDecimal initiationRate = "USD".equals(destinationCurrency)
                ? BigDecimal.ONE
                : exchangeRateService.getRate(destinationCurrency, "USD");
        disbursement.setTotalDebitedUsd(totalDebited.multiply(initiationRate).setScale(2, RoundingMode.HALF_UP));
        disbursement.setCommissionRate(commissionService.getGatewayRate());
        disbursement.setDestination(displayDestination);
        disbursement.setProvider("FLUTTERWAVE");
        disbursement.setChannel("FLUTTERWAVE_" + transferType);
        disbursement.setReference(reference);
        disbursement.setStatus(DisbursementStatus.PENDING);
        // Real fix: previously always "KES" regardless of what
        // destinationCurrency actually was — now records the real payout
        // currency, whatever the request specified.
        disbursement.setCurrency(destinationCurrency);

        try {
            FlutterwaveService.TransferResult result;
            if ("BANK".equals(transferType)) {
                result = flutterwaveService.initiateBankTransfer(
                        request.getFlutterwaveAccountNumber(), request.getFlutterwaveAccountBank(),
                        sourceCurrency, destinationCurrency, request.getAmount(), reference,
                        request.getRemarks(), firstName, lastName);
            } else {
                result = flutterwaveService.initiateTransfer(
                        request.getFlutterwaveAccountNumber(), request.getFlutterwaveAccountBank(),
                        sourceCurrency, destinationCurrency, request.getAmount(), reference,
                        request.getRemarks(), firstName, lastName);
            }

            log.info("Flutterwave disbursement: userId={} reference={} type={} amount={} destination={}",
                    userId, reference, transferType, request.getAmount(), displayDestination);

            if (!result.success()) {
                disbursement.setStatus(DisbursementStatus.FAILED);
                disbursement.setFailureReason(result.message());
                disbursementRepository.save(disbursement);
                return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.message());
            }

            disbursement.setProviderReference(result.transferId());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    "Disbursement queued with Flutterwave — your wallet will be debited once Flutterwave confirms the payout.");

        } catch (Exception e) {
            log.error("Flutterwave transfer threw: userId={} reference={}", userId, reference, e);
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason("Flutterwave transfer initiation failed: " + e.getMessage());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    disbursement.getFailureReason());
        }
    }

    /**
     * Reconciliation from Flutterwave's transfer.disburse webhook. Keyed
     * by transferId (Flutterwave's own numeric id, stored as
     * providerReference at initiation) rather than our own reference,
     * since that's what the webhook payload's data.id carries — see
     * PaymentCallbackController.flutterwaveWebhook.
     */
    @Transactional
    public void completeFlutterwaveDisbursement(String transferId, boolean success, String statusDesc) {
        Disbursement d = disbursementRepository.findByProviderReference(transferId).orElse(null);
        if (d == null) {
            log.warn("Flutterwave transfer webhook for unknown transferId={} — ignoring", transferId);
            return;
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            log.warn("Flutterwave transfer webhook for already-finalized disbursement id={} status={} — ignoring duplicate",
                    d.getId(), d.getStatus());
            return;
        }

        if (success) {
            d.setStatus(DisbursementStatus.SUCCESS);

            if (d.getWalletId() != null) {
                // First touch of the wallet for this disbursement — same
                // negative-balance handling as every other provider's
                // completeXDisbursement here.
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));

                // d.getTotalDebitedUsd() was locked in at INITIATION time
                // (see processFlutterwaveDisbursement and
                // Disbursement.totalDebitedUsd's own javadoc) — read here,
                // not re-converted, so this stays consistent with
                // whatever rate was current when the withdrawal was first
                // requested, and so adminApproveDisbursement (the manual
                // path) reads this exact same value rather than needing
                // its own independent conversion call. Falls back to
                // converting at completion time (today's rate, keyed off
                // d.getCurrency() — the real payout currency now, since
                // the earlier hardcoding bug was fixed) only for a legacy
                // disbursement created before this field existed.
                BigDecimal debitAmountUsd;
                if (d.getTotalDebitedUsd() != null) {
                    debitAmountUsd = d.getTotalDebitedUsd();
                } else {
                    BigDecimal debitAmountNative = d.getTotalDebited() != null ? d.getTotalDebited() : d.getAmount();
                    String nativeCurrency = d.getCurrency() != null ? d.getCurrency() : "KES";
                    BigDecimal rate = "USD".equals(nativeCurrency)
                            ? BigDecimal.ONE
                            : exchangeRateService.getRate(nativeCurrency, "USD");
                    debitAmountUsd = debitAmountNative.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                }

                BigDecimal newBalance = wallet.getBalance().subtract(debitAmountUsd);
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    log.error("Wallet {} balance went negative ({}) debiting confirmed Flutterwave disbursement id={} — needs manual reconciliation",
                            wallet.getId(), newBalance, d.getId());
                }
                wallet.setBalance(newBalance);
                walletRepository.save(wallet);

                disbursementRepository.save(d);
                transactionRecorder.record(d.getUserId(), d.getWalletId(), debitAmountUsd, d, d.getReference());
                commissionService.recordGatewayCommissionFromDisbursement(d);

                // Email deliberately shows d.getAmount()/d.getCurrency() —
                // the real native amount Flutterwave actually paid out,
                // not the wallet-side USD debit — the meaningful,
                // externally-verifiable fact for the customer.
                // d.getDestination() shown exactly as given, no masking.
                String exchangeRateInfo = null;
                BigDecimal nativeDebitForRate = d.getTotalDebited() != null ? d.getTotalDebited() : d.getAmount();
                if (nativeDebitForRate.compareTo(BigDecimal.ZERO) != 0 && d.getCurrency() != null
                        && !"USD".equals(d.getCurrency())) {
                    BigDecimal impliedRate = debitAmountUsd.divide(nativeDebitForRate, 6, RoundingMode.HALF_UP);
                    exchangeRateInfo = "1 " + d.getCurrency() + " = " + impliedRate.toPlainString() + " USD";
                }
                String senderName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
                d.setSenderName(senderName);
                disbursementRepository.save(d);
                emailService.sendDisbursementSuccess(wallet.getAccountNumber(), d.getAmount().toPlainString(),
                        d.getCurrency(), d.getDestination(), d.getReference(),
                        new EmailService.DisbursementDetails("Flutterwave", exchangeRateInfo,
                                senderName, wallet.getAccountNumber(), wallet.getId()));
            } else {
                disbursementRepository.save(d);
            }

            log.info("Flutterwave disbursement completed: id={} transferId={}", d.getId(), transferId);
        } else {
            // No refund needed — the wallet was never debited for a
            // PENDING Flutterwave disbursement (see processFlutterwaveDisbursement above).
            d.setStatus(DisbursementStatus.FAILED);
            d.setFailureReason(statusDesc);
            disbursementRepository.save(d);

            if (d.getWalletId() != null) {
                walletRepository.findById(d.getWalletId()).ifPresent(wallet -> {
                    String senderName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
                    emailService.sendDisbursementFailed(wallet.getAccountNumber(),
                            d.getAmount().toPlainString(), d.getCurrency(), statusDesc, d.getDestination(),
                            new EmailService.DisbursementDetails("Flutterwave", null,
                                    senderName, wallet.getAccountNumber(), wallet.getId()));
                });
            }

            log.warn("Flutterwave disbursement failed: id={} transferId={} reason={}", d.getId(), transferId, statusDesc);
        }
    }
}