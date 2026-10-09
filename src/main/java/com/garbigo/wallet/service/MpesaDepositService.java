package com.premisave.wallet.service;

import com.premisave.wallet.dto.DepositRequest;
import com.premisave.wallet.dto.MpesaStkPushRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DepositStatus;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DepositRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * M-Pesa deposit business logic — STK Push initiation, plus reconciling
 * its callback. Split out of the former all-providers DepositService —
 * mirrors how MpesaService already sits alone at the API-integration
 * layer; this is the same split applied one layer up, at business
 * logic/orchestration.
 *
 * Migrated to the Deposit entity (Stage 3) — same pattern
 * NowPaymentsDepositService pioneered (Stage 2): a dedicated Deposit
 * record instead of a generic Transaction row with detail packed into a
 * free-text description, plus DepositTransactionRecorder creating the
 * matching Transaction row on confirmation for the unified history feed.
 *
 * CURRENCY CONVERSION: the wallet is now fixed at USD (see Wallet.currency),
 * but M-Pesa only ever operates in KES — the STK push itself still
 * requests/receives a KES amount from the user, since that's what M-Pesa
 * understands and what a Kenyan user naturally thinks in. The KES->USD
 * conversion happens ONLY at confirmation time (creditWalletFromStkCallback),
 * using the amount Safaricom's own callback confirms was actually paid —
 * not the originally-requested amount — since that's the real, confirmed
 * transaction, and the exchange rate could genuinely differ by the few
 * seconds/minutes between STK push initiation and PIN confirmation.
 * deposit.amount/currency end up representing the WALLET-side (USD)
 * value; the original KES amount is preserved on priceAmount/priceCurrency
 * — same convention FlutterwaveDepositService/NowPaymentsDepositService
 * already use for their own non-USD pricing, reused here rather than
 * inventing a different pattern for M-Pesa specifically.
 *
 * Called from DepositService.initiateDeposit (dispatcher) for initiation,
 * and directly from PaymentCallbackController for the STK webhook handler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaDepositService {

    private final WalletRepository walletRepository;
    private final DepositRepository depositRepository;
    private final MpesaService mpesaService;
    private final DepositTransactionRecorder depositTransactionRecorder;
    private final EmailService emailService;
    private final UserNameResolver userNameResolver;
    private final ExchangeRateService exchangeRateService;

    // ─── M-Pesa STK Push ─────────────────────────────────────────────────────

    /**
     * request.getAmount() here is the KES amount requested via STK push —
     * unchanged from before, since M-Pesa only understands KES. No
     * conversion happens at this point; it happens once at confirmation
     * (see creditWalletFromStkCallback), using the confirmed amount from
     * Safaricom's own callback.
     */
    public PaymentResponse initiateMpesaDeposit(String userId, DepositRequest request,
                                                 Wallet wallet, String idempotencyKey) {
        String phoneNumber = (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank())
                ? request.getPhoneNumber()
                : wallet.getMpesaPhoneNumber();

        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException(
                    "Please enter a phone number for this M-Pesa deposit, or save one in your wallet settings so you don't have to type it every time.");
        }

        MpesaStkPushRequest stkRequest = new MpesaStkPushRequest();
        stkRequest.setPhoneNumber(phoneNumber);
        stkRequest.setAmount(request.getAmount());
        stkRequest.setAccountReference("PREMISAVE");

        MpesaService.StkPushResult result = mpesaService.initiateStkPush(stkRequest);

        if (!result.success()) {
            log.warn("M-Pesa STK push rejected: userId={} reason={}", userId, result.errorMessage());
            return new PaymentResponse(false, null, "M-Pesa STK push failed: " + result.errorMessage());
        }

        log.info("M-Pesa STK push: userId={} checkoutId={}", userId, result.checkoutRequestId());

        savePendingDeposit(userId, wallet.getId(), request.getAmount(), "MPESA_STK",
                phoneNumber, result.checkoutRequestId());

        String message = (result.customerMessage() != null && !result.customerMessage().isBlank())
                ? result.customerMessage()
                : "M-Pesa STK push sent. Enter your PIN to complete the deposit.";

        return new PaymentResponse(true, result.checkoutRequestId(), message);
    }

    // ─── Callbacks ───────────────────────────────────────────────────────────

    /**
     * amount here is the CONFIRMED KES amount from Safaricom's own STK
     * callback — converted to USD before it ever touches wallet.balance,
     * since the wallet is fixed at USD. Uses ExchangeRateService (the
     * Redis/MongoDB-cached layer), never FxRateService directly — a live
     * FX API call has no place sitting inline in a webhook handler.
     */
    @Transactional
    public void creditWalletFromStkCallback(String checkoutRequestId, BigDecimal amount,
                                             String mpesaReceiptNumber, String phoneNumber) {
        Deposit deposit = depositRepository.findByReference(checkoutRequestId)
                .orElseThrow(() -> new IllegalStateException(
                        "No pending deposit found for CheckoutRequestID=" + checkoutRequestId));

        if (deposit.getStatus() == DepositStatus.SUCCESS) {
            log.warn("STK callback already processed for CheckoutRequestID={} — skipping duplicate credit",
                    checkoutRequestId);
            return;
        }

        Wallet wallet = walletRepository.findById(deposit.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + deposit.getWalletId()));

        BigDecimal rate = exchangeRateService.getRate("KES", "USD");
        BigDecimal usdAmount = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);

        wallet.setBalance(wallet.getBalance().add(usdAmount));
        walletRepository.save(wallet);

        deposit.setStatus(DepositStatus.SUCCESS);
        deposit.setAmount(usdAmount);
        deposit.setCurrency(Currency.USD);
        deposit.setPriceAmount(amount);
        deposit.setPriceCurrency("kes");
        deposit.setProviderReference(mpesaReceiptNumber);
        // Self-deposit — sender and recipient are the same wallet owner,
        // so senderName stays null; only recipientName is meaningful here.
        String recipientName = userNameResolver.resolveNameSafely(wallet.getAccountNumber());
        deposit.setRecipientName(recipientName);
        depositRepository.save(deposit);

        depositTransactionRecorder.record(deposit.getUserId(), deposit.getWalletId(), usdAmount,
                deposit, deposit.getReference());

        // wallet.getAccountNumber() IS the user's email — confirmed
        // consistently across ManualAdjustment/Payment/Transfer's own
        // documented use of this same field for the same purpose.
        // phoneNumber shown in full, no masking.
        String exchangeRateInfo = "1 KES = " + rate.toPlainString() + " USD";
        emailService.sendDepositConfirmation(wallet.getAccountNumber(), usdAmount.toPlainString(),
                deposit.getCurrency().name(), deposit.getReference(), wallet.getBalance().toPlainString(),
                new EmailService.DepositDetails("M-Pesa", exchangeRateInfo, phoneNumber,
                        null, mpesaReceiptNumber, recipientName, wallet.getAccountNumber(), wallet.getId()));

        log.info("Wallet credited via M-Pesa STK: checkoutRequestId={} kesAmount={} usdAmount={} rate={} receipt={}",
                checkoutRequestId, amount, usdAmount, rate, mpesaReceiptNumber);
    }

    @Transactional
    public void markStkTransactionFailed(String checkoutRequestId, String resultDesc) {
        depositRepository.findByReference(checkoutRequestId).ifPresentOrElse(deposit -> {
            deposit.setStatus(DepositStatus.FAILED);
            deposit.setFailureReason(resultDesc);
            depositRepository.save(deposit);
            log.warn("STK push failed: checkoutRequestId={} reason={}", checkoutRequestId, resultDesc);
        }, () -> log.warn("STK failure callback for unknown CheckoutRequestID={}: {}", checkoutRequestId, resultDesc));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Saved at initiation with the REQUESTED KES amount/currency — this
     * is a transient, pending-state record that gets fully overwritten
     * (amount, currency, priceAmount, priceCurrency) once
     * creditWalletFromStkCallback confirms the actual paid amount, so no
     * conversion happens here yet.
     */
    private void savePendingDeposit(String userId, String walletId, BigDecimal amount,
                                     String channel, String source, String reference) {
        Deposit deposit = new Deposit();
        deposit.setUserId(userId);
        deposit.setWalletId(walletId);
        deposit.setAmount(amount);
        deposit.setCurrency(Currency.KES);
        deposit.setProvider("MPESA");
        deposit.setChannel(channel);
        deposit.setSource(source);
        deposit.setStatus(DepositStatus.PENDING);
        deposit.setReference(reference);
        depositRepository.save(deposit);
    }
}