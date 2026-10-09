package com.premisave.wallet.service;

import com.premisave.wallet.exception.DuplicateTransactionException;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final TransactionRepository transactionRepository;
    private final DisbursementRepository disbursementRepository;

    /**
     * Checks if a transaction OR disbursement with the given reference already
     * exists. Throws exception if a duplicate is found.
     *
     * Checking TransactionRepository alone isn't enough for disbursement
     * callers (processDisbursement, processB2BPayment, processB2CTopUp,
     * processB2PochiPayment) — a Disbursement row is created immediately with
     * status PENDING/SUCCESS/FAILED, but the matching Transaction row is only
     * created later once success is actually confirmed (synchronously for
     * Stripe, or via an async webhook for M-Pesa/PayPal). Without this check,
     * resubmitting the same reference while the first disbursement is still
     * PENDING would sail past idempotency and trigger a second real payout.
     */
    public void checkIdempotency(String reference) {
        if (reference == null || reference.trim().isEmpty()) {
            return; // No reference = no idempotency check
        }

        if (transactionRepository.existsByReference(reference)
                || disbursementRepository.existsByReference(reference)) {
            log.warn("Duplicate transaction/disbursement detected with reference: {}", reference);
            throw new DuplicateTransactionException("A transaction with reference " + reference + " has already been processed");
        }
    }

    /**
     * Checks idempotency and returns true if it's a new transaction/disbursement.
     */
    public boolean isNewTransaction(String reference) {
        if (reference == null || reference.trim().isEmpty()) {
            return true;
        }
        return !transactionRepository.existsByReference(reference)
                && !disbursementRepository.existsByReference(reference);
    }

    /**
     * Record a transaction reference after successful processing (for future use)
     */
    @Transactional
    public void recordTransactionReference(String reference, String transactionId) {
        // Currently we rely on Transaction entity, but this can be extended with a dedicated idempotency log if needed
        log.info("Transaction reference recorded: {} -> TX ID: {}", reference, transactionId);
    }
}