package com.leap.leaplaughlove.marketdata.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Interface to represent the repository for ingested quotes in the market data system.
 * This repository provides methods to perform CRUD operations and custom queries on quotes.
 */
public interface QuoteRepository extends JpaRepository<Quote, UUID> {

    /**
     * Finds the most recent quote for the given instrument symbol.
     * @param symbol the instrument symbol
     * @return the latest quote for the symbol, if any
     */
    Optional<Quote> findFirstByInstrument_SymbolOrderByQuoteTimestampDesc(String symbol);
}
