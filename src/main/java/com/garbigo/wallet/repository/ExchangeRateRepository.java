package com.premisave.wallet.repository;

import com.premisave.wallet.entity.ExchangeRate;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ExchangeRateRepository extends MongoRepository<ExchangeRate, String> {
    Optional<ExchangeRate> findByBaseCurrencyAndQuoteCurrency(String baseCurrency, String quoteCurrency);
}