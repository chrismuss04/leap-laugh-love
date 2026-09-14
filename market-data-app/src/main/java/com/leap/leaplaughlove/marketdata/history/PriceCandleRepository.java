package com.leap.leaplaughlove.marketdata.history;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface PriceCandleRepository extends JpaRepository<PriceCandle, UUID> {
    Page<PriceCandle> findByInstrument_SymbolAndBucketStartBetweenOrderByBucketStartDesc(
            String symbol, OffsetDateTime from, OffsetDateTime to, Pageable pageable);
}
