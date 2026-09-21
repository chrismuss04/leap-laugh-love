package com.leap.leaplaughlove.marketdata.history;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface to represent the repository for OHLC price candles in the market data system.
 * This repository provides methods to perform CRUD operations and custom queries on candles.
 */
public interface PriceCandleRepository extends JpaRepository<PriceCandle, UUID> {

    /**
     * Finds a newest-first page of candles for the given instrument symbol whose bucket
     * falls within the given time range, at one bucket width. Callers always pin the width:
     * the table holds several widths for the same instant, so an unfiltered range query would
     * interleave 60s candles with the 1h and 1d rollups covering the same period.
     * @param symbol the instrument symbol
     * @param bucketSeconds the bucket width, in seconds, to return
     * @param from the start of the time range, inclusive
     * @param to the end of the time range, inclusive
     * @param pageable the page and size to retrieve
     * @return the matching page of candles, newest first
     */
    Page<PriceCandle> findByInstrument_SymbolAndBucketSecondsAndBucketStartBetweenOrderByBucketStartDesc(
            String symbol, int bucketSeconds, OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    /**
     * Returns the most recent candle for the given symbol, at any bucket width. Used to resume
     * the simulation from where it left off instead of restarting at the instrument's seed
     * price, which would put a discontinuity in the price series at every application restart.
     * Ties on bucket start are broken towards the narrowest bucket, whose close is the latest
     * price in that instant.
     * @param symbol the instrument symbol
     * @return the newest candle for the symbol, or empty if it has none yet
     */
    Optional<PriceCandle> findFirstByInstrument_SymbolOrderByBucketStartDescBucketSecondsAsc(String symbol);

    /**
     * Reports whether any candle already exists for the given instrument, at any bucket width.
     * Backs the historical backfill's idempotency check.
     * @param instrumentId the instrument to check
     * @return true if the instrument already has at least one candle
     */
    boolean existsByInstrument_InstrumentId(UUID instrumentId);
}
