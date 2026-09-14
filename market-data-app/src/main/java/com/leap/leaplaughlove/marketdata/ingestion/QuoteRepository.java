package com.leap.leaplaughlove.marketdata.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface QuoteRepository extends JpaRepository<Quote, UUID> {
    Optional<Quote> findFirstByInstrument_SymbolOrderByQuoteTimestampDesc(String symbol);
}
