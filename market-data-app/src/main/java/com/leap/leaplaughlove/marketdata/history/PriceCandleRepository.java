package com.leap.leaplaughlove.marketdata.history;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Interface to represent the repository for OHLC price candles in the market data system.
 * This repository provides methods to perform CRUD operations and custom queries on candles.
 */
public interface PriceCandleRepository extends JpaRepository<PriceCandle, UUID> {

    /**
     * Finds a newest-first page of candles for the given instrument symbol whose bucket
     * falls within the given time range.
     * @param symbol the instrument symbol
     * @param from the start of the time range, inclusive
     * @param to the end of the time range, inclusive
     * @param pageable the page and size to retrieve
     * @return the matching page of candles, newest first
     */
    Page<PriceCandle> findByInstrument_SymbolAndBucketStartBetweenOrderByBucketStartDesc(
            String symbol, OffsetDateTime from, OffsetDateTime to, Pageable pageable);
}
