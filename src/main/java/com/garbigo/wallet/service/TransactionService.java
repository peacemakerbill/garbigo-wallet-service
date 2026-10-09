package com.premisave.wallet.service;

import com.premisave.wallet.dto.TransactionResponse;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public List<TransactionResponse> getTransactionHistory(String userId) {
        List<Transaction> transactions = transactionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return transactions.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    private TransactionResponse mapToResponse(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getType(),
                tx.getStatus(),
                tx.getAmount(),
                tx.getCurrency().name(),
                tx.getDescription(),
                tx.getCreatedAt()
        );
    }
}