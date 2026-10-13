package com.leap.leaplaughlove.reporting.analyst;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * How filled orders spread across value bands each month, with the typical order's value.
 * @param window the months reported on
 * @param months one entry per month in the window, oldest first
 */
public record OrderSizeReport(ReportWindow window, List<Month> months) {

    /**
     * One month's filled orders by value.
     * @param month the month
     * @param filledOrders how many orders filled
     * @param meanValue mean filled order value; null when nothing filled
     * @param medianValue median filled order value; null when nothing filled
     * @param bands every band, smallest first, including empty ones
     */
    public record Month(YearMonth month, long filledOrders, BigDecimal meanValue, BigDecimal medianValue,
                        List<Band> bands) {}

    /**
     * One value band's filled orders in the month.
     * @param band the band
     * @param from the band's lower bound, inclusive; null for the lowest band
     * @param to the band's upper bound, exclusive; null for the highest band
     * @param orders how many filled orders fell in the band
     * @param value their combined value
     */
    public record Band(OrderSizeBand band, BigDecimal from, BigDecimal to, long orders, BigDecimal value) {}
}
