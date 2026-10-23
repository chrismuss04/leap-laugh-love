package com.leap.leaplaughlove.reporting.analyst;

import java.math.BigDecimal;

/**
 * Bands a filled order falls into by its value (quantity times fill price). Each band includes its
 * lower bound and excludes its upper one, so 1,000.00 is in {@link #FROM_1K} and 999.99 is not.
 * Declared in ascending order with no gaps: the order-size query relies on both.
 */
public enum OrderSizeBand {
    /** Under 1,000. */
    UNDER_1K(null, "1000"),
    /** 1,000 to under 5,000. */
    FROM_1K(  "1000", "5000"),
    /** 5,000 to under 10,000. */
    FROM_5K(  "5000", "10000"),
    /** 10,000 to under 25,000. */
    FROM_10K("10000", "25000"),
    /** 25,000 and over. */
    FROM_25K("25000", null);

    private final BigDecimal from;
    private final BigDecimal to;

    OrderSizeBand(String from, String to) {
        this.from = from == null ? null : new BigDecimal(from);
        this.to = to == null ? null : new BigDecimal(to);
    }

    /**
     * The band's lower bound.
     * @return the smallest value in the band, or null for the lowest band
     */
    public BigDecimal from() {
        return from;
    }

    /**
     * The band's upper bound.
     * @return the value the band stops short of, or null for the highest band
     */
    public BigDecimal to() {
        return to;
    }
}
