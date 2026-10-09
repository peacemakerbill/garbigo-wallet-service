package com.premisave.wallet.repository;

import com.premisave.wallet.entity.CompanyLedgerEntry;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface CompanyLedgerRepository extends MongoRepository<CompanyLedgerEntry, String> {
    List<CompanyLedgerEntry> findByTypeOrderByCreatedAtDesc(String type);

    List<CompanyLedgerEntry> findBySourceTypeAndSourceId(String sourceType, String sourceId);

    /** For date-ranged P&L reporting, e.g. AdminWalletController's existing getDailyReport/getSystemSummary. */
    List<CompanyLedgerEntry> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
}