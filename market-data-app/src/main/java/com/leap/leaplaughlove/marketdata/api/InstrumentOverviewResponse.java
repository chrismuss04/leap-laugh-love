package com.leap.leaplaughlove.marketdata.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A market snapshot with a shared UTC performance period.
 * @param period the period changes are measured over: today, week or month
 * @param periodStart when the period started
 * @param asOf when the snapshot was taken
 * @param instruments every listed instrument
 */
public record InstrumentOverviewResponse(String period, OffsetDateTime periodStart,
        OffsetDateTime asOf, List<Instrument> instruments, long total, int page, int size,
        List<Instrument> gainers, List<Instrument> losers) {

    public InstrumentOverviewResponse(String period, OffsetDateTime periodStart, OffsetDateTime asOf,
            List<Instrument> instruments) {
        this(period, periodStart, asOf, instruments, instruments.size(), 0, instruments.size(), List.of(), List.of());
    }

    /**
     * Unknown metadata and unavailable performance are represented by null.
     * @param symbol the ticker symbol
     * @param name the instrument's name
     * @param assetClass the asset class: EQUITY, FX or CRYPTO
     * @param currency the currency it trades in
     * @param price the latest price
     * @param asOf when the latest price was set
     * @param baselinePrice the price at the start of the period
     * @param change the change in price since the start of the period
     * @param changePercent the change as a percentage of the baseline price
     */
    public record Instrument(String symbol, String name, String assetClass, String currency,
            BigDecimal price, OffsetDateTime asOf, BigDecimal baselinePrice,
            BigDecimal change, BigDecimal changePercent) {}
}
