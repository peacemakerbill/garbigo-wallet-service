package com.garbigo.wallet.repository;

import com.garbigo.wallet.entity.ExchangeRate;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ExchangeRateRepository extends MongoRepository<ExchangeRate, String> {
    Optional<ExchangeRate> findByBaseCurrencyAndQuoteCurrency(String baseCurrency, String quoteCurrency);
}