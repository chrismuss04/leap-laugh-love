package com.leap.leaplaughlove.marketdata.history;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface to represent the repository for OHLC price candles in the market data system.
 * This repository provides methods to perform CRUD operations and custom queries on candles.
 */
public interface PriceCandleRepository extends JpaRepository<PriceCandle, PriceCandle.Key> {

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
     * Returns the most recent candle for the given symbol at one bucket width - a single
     * backwards step along the primary key.
     * @param symbol the instrument symbol
     * @param bucketSeconds the bucket width, in seconds
     * @return the newest candle of that width, or empty if there is none
     */
    Optional<PriceCandle> findFirstByInstrument_SymbolAndBucketSecondsOrderByBucketStartDesc(
            String symbol, int bucketSeconds);

    /**
     * Returns the most recent candle for the given symbol across the given bucket widths. Used
     * to resume the simulation from where it left off instead of restarting at the instrument's
     * seed price, which would put a discontinuity in the price series at every application
     * restart. Ties on bucket start are broken towards the narrowest bucket, whose close is the
     * latest price in that instant.
     *
     * <p>Asks for each width's newest candle separately: the primary key leads with the width
     * after the instrument, so "newest at any width" as one query would read every candle the
     * instrument has.
     * @param symbol the instrument symbol
     * @param bucketSeconds the bucket widths to consider
     * @return the newest candle for the symbol, or empty if it has none yet
     */
    default Optional<PriceCandle> findNewest(String symbol, Collection<Integer> bucketSeconds) {
        return bucketSeconds.stream()
                .sorted()
                .map(width -> findFirstByInstrument_SymbolAndBucketSecondsOrderByBucketStartDesc(symbol, width))
                .flatMap(Optional::stream)
                .reduce((newest, candle) -> candle.getBucketStart().isAfter(newest.getBucketStart()) ? candle : newest);
    }

    /**
     * Reports whether any candle already exists for the given instrument, at any bucket width.
     * Backs the historical backfill's idempotency check.
     * @param instrumentId the instrument to check
     * @return true if the instrument already has at least one candle
     */
    boolean existsByInstrument_InstrumentId(UUID instrumentId);

    /**
     * Deletes every candle of one width older than a cutoff, in a single statement. Written as a
     * bulk {@code @Modifying} query rather than a derived {@code deleteBy...}, which would load
     * each row as an entity and delete it individually - the opposite of what a prune needs.
     * @param bucketSeconds the bucket width to prune
     * @param cutoff candles whose bucket starts strictly before this are deleted
     * @return the number of candles deleted
     */
    @Modifying
    @Query("DELETE FROM PriceCandle c WHERE c.bucketSeconds = :bucketSeconds AND c.bucketStart < :cutoff")
    int deleteByBucketSecondsAndBucketStartBefore(@Param("bucketSeconds") int bucketSeconds,
                                                   @Param("cutoff") OffsetDateTime cutoff);
}
