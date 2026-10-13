package com.leap.leaplaughlove.marketdata.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** A market snapshot with a shared UTC performance period. */
public record InstrumentOverviewResponse(String period, OffsetDateTime periodStart,
        OffsetDateTime asOf, List<Instrument> instruments) {

    /** Unknown metadata and unavailable performance are represented by null. */
    public record Instrument(String symbol, String name, String assetClass, String currency,
            BigDecimal price, OffsetDateTime asOf, BigDecimal baselinePrice,
            BigDecimal change, BigDecimal changePercent) {}
}
