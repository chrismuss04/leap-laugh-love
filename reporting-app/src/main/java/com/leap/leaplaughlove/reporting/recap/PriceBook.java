package com.leap.leaplaughlove.reporting.recap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Daily closing prices by trading symbol, keyed by the UTC day each daily candle covers. A day's
 * close is the price at the end of that day, so the close keyed by the last day of a month is
 * the price the next month starts from.
 */
public final class PriceBook {

    private final Map<String, NavigableMap<LocalDate, BigDecimal>> closes;

    /**
     * Creates a price book.
     * @param closes each symbol's closes, keyed by the day the candle covers
     */
    public PriceBook(Map<String, ? extends Map<LocalDate, BigDecimal>> closes) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> copy = new TreeMap<>();
        closes.forEach((symbol, byDay) -> copy.put(symbol, new TreeMap<>(byDay)));
        this.closes = copy;
    }

    /**
     * A price book with no prices, for months with nothing to price.
     * @return an empty price book
     */
    public static PriceBook empty() {
        return new PriceBook(Map.of());
    }

    /**
     * The price at the end of a day: the close of that day's candle, or of the latest earlier one
     * when the day has none.
     * @param symbol the trading symbol
     * @param day the UTC day
     * @return the close, or empty when the symbol has no candle on or before the day
     */
    public Optional<BigDecimal> closeOn(String symbol, LocalDate day) {
        NavigableMap<LocalDate, BigDecimal> byDay = closes.get(symbol);
        if (byDay == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byDay.floorEntry(day)).map(Map.Entry::getValue);
    }
}
