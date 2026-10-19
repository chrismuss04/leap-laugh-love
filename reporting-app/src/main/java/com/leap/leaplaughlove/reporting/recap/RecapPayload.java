package com.leap.leaplaughlove.reporting.recap;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * One client's recap of one UTC month: everything the recap cards show, stored as JSON in
 * reporting.client_recaps so opening a recap never recomputes it. Its shape is version
 * {@value RecapCalculator#SCHEMA_VERSION}; a change to it bumps that number.
 *
 * <p>Returns are trading returns: the gain on the positions the client held or traded in the
 * month, valued at daily closes, over the most money each position had in it at once. Cash
 * deposits and withdrawals play no part.
 *
 * @param month the month the recap covers
 * @param quiet true when the client filled no orders in the month. Positions held from before
 *              still move, so a quiet recap can still carry a trading return, gainers and losers,
 *              a benchmark difference and a ranking; only the trading figures are empty
 * @param moneyMoved how much the client traded
 * @param topTickers up to five symbols by value traded, most first
 * @param topGainer the symbol with the best return, or null when none gained
 * @param topGainers up to five symbols that gained, best first
 * @param biggestLosers up to five symbols that lost, worst first
 * @param tradingReturn the month's trading return across every priced position
 * @param benchmark the S&amp;P 500's move over the month, or null when it has no prices
 * @param instrumentsTraded how many different symbols the client traded
 * @param topSector the sector the client traded most, or null when none of the symbols has one
 * @param topPercent the smallest top slice of the month's clients, by trading return, the client is
 *                   in; null until every client's recap is ranked, and when not in the top half
 */
public record RecapPayload(
        YearMonth month,
        boolean quiet,
        MoneyMoved moneyMoved,
        List<TickerActivity> topTickers,
        TopGainer topGainer,
        List<SymbolReturn> topGainers,
        List<SymbolReturn> biggestLosers,
        TradingReturn tradingReturn,
        Benchmark benchmark,
        int instrumentsTraded,
        TopSector topSector,
        Integer topPercent
) {

    /**
     * The same recap with the client's ranking set.
     * @param topPercent the client's top slice, or null when not shown
     * @return a copy with the ranking
     */
    public RecapPayload withTopPercent(Integer topPercent) {
        return new RecapPayload(month, quiet, moneyMoved, topTickers, topGainer, topGainers, biggestLosers,
                tradingReturn, benchmark, instrumentsTraded, topSector, topPercent);
    }

    /**
     * The value of the month's fills.
     * @param total the sum of quantity times price over every fill, buys and sells
     * @param bought the value of the buy fills
     * @param sold the value of the sell fills
     * @param filledOrders how many orders filled
     * @param accounts how many of the client's accounts traded
     */
    public record MoneyMoved(BigDecimal total, BigDecimal bought, BigDecimal sold, int filledOrders,
                             int accounts) {}

    /**
     * One symbol's trading in the month.
     * @param symbol the trading symbol
     * @param tradedValue the value of its fills, buys and sells
     * @param filledOrders how many of its orders filled
     * @param closes its daily closes through the month, oldest first, for a sparkline
     */
    public record TickerActivity(String symbol, BigDecimal tradedValue, int filledOrders, List<BigDecimal> closes) {}

    /**
     * One symbol's trading return.
     * @param symbol the trading symbol
     * @param returnPct the gain as a percentage of the most money in the position at once
     * @param pnl the gain in money, negative for a loss
     */
    public record SymbolReturn(String symbol, BigDecimal returnPct, BigDecimal pnl) {}

    /**
     * The symbol with the best return.
     * @param symbol the trading symbol
     * @param returnPct the gain as a percentage of the most money in the position at once
     * @param pnl the gain in money
     * @param closes its daily closes through the month, oldest first, for a sparkline
     */
    public record TopGainer(String symbol, BigDecimal returnPct, BigDecimal pnl, List<BigDecimal> closes) {}

    /**
     * The month's trading return across every priced position.
     * @param returnPct the total gain as a percentage of the total of each position's peak, or null when
     *                  nothing could be priced
     * @param pnl the total gain in money
     * @param dailyPnl the gain so far at the end of each day of the month, for a chart
     */
    public record TradingReturn(BigDecimal returnPct, BigDecimal pnl, List<BigDecimal> dailyPnl) {}

    /**
     * The market index the client's return is compared with.
     * @param symbol the index symbol
     * @param name the index's display name
     * @param returnPct the index's move over the month, as a percentage
     * @param differencePts the client's trading return minus the index's, in percentage points;
     *                      null when the client has no trading return
     */
    public record Benchmark(String symbol, String name, BigDecimal returnPct, BigDecimal differencePts) {}

    /**
     * The sector the client traded most.
     * @param sector the GICS sector name
     * @param label the short name the recap card shows
     * @param tradedValue the value traded in the sector
     */
    public record TopSector(String sector, String label, BigDecimal tradedValue) {}
}
