package com.leap.leaplaughlove.reporting.recap;

import com.leap.leaplaughlove.reporting.recap.RecapFill.Side;
import com.leap.leaplaughlove.reporting.recap.RecapPayload.SymbolReturn;
import com.leap.leaplaughlove.reporting.recap.RecapPayload.TickerActivity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RecapCalculator")
class RecapCalculatorTest {

    // September 2026: starts on a Tuesday, 30 days, 22 weekdays.
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final LocalDate AUG_31 = LocalDate.of(2026, 8, 31);
    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Map<String, String> SECTORS = Map.of(
            "AAPL", "Information Technology",
            "NVDA", "Information Technology",
            "TSLA", "Consumer Discretionary",
            "XOM", "Energy",
            "NEE", "Utilities");

    private final RecapCalculator calculator = new RecapCalculator();

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    private static RecapFill fill(String symbol, Side side, long quantity, String price, String filledAt) {
        return fill(ACCOUNT, symbol, side, quantity, price, filledAt);
    }

    private static RecapFill fill(UUID account, String symbol, Side side, long quantity, String price, String filledAt) {
        return new RecapFill(account, symbol, side, quantity, dec(price), OffsetDateTime.parse(filledAt));
    }

    /** Prices from alternating day and close arguments: prices("AAPL", AUG_31, "200", SEP_30, "220"). */
    private static Map<LocalDate, BigDecimal> closes(Object... dayThenClose) {
        Map<LocalDate, BigDecimal> closes = new HashMap<>();
        for (int i = 0; i < dayThenClose.length; i += 2) {
            closes.put((LocalDate) dayThenClose[i], dec((String) dayThenClose[i + 1]));
        }
        return closes;
    }

    private RecapPayload recap(List<RecapFill> fills, Map<String, Map<LocalDate, BigDecimal>> prices) {
        return calculator.calculate(SEP, fills, new PriceBook(prices), SECTORS);
    }

    // AAPL: 10 held from August, up from 200 to 220 over September: +200 on 2,000 put in, 10%.
    private static final List<RecapFill> AAPL_HELD = List.of(fill("AAPL", Side.BUY, 10, "100", "2026-08-10T14:00:00Z"));
    private static final Map<LocalDate, BigDecimal> AAPL_PRICES = closes(AUG_31, "200", SEP_30, "220");

    // NVDA: bought and sold inside September, no candles at all: +100 on 500 put in, 20%.
    private static final List<RecapFill> NVDA_ROUND_TRIP = List.of(
            fill("NVDA", Side.BUY, 10, "50", "2026-09-03T14:00:00Z"),
            fill("NVDA", Side.SELL, 10, "60", "2026-09-20T14:00:00Z"));

    private static List<RecapFill> concat(List<RecapFill> first, List<RecapFill> second) {
        List<RecapFill> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    @Nested
    @DisplayName("money moved")
    class MoneyMoved {

        @Test
        @DisplayName("sums the month's fills, buys and sells, and spreads them over its weekdays")
        void totalsTheMonth() {
            List<RecapFill> fills = List.of(
                    fill("AAPL", Side.BUY, 1, "90", "2026-08-31T23:59:59Z"),          // August: not counted
                    fill("NVDA", Side.BUY, 10, "50", "2026-09-03T14:00:00Z"),
                    fill("NVDA", Side.SELL, 10, "60", "2026-09-20T14:00:00Z"),
                    fill(OTHER_ACCOUNT, "AAPL", Side.BUY, 2, "100", "2026-09-10T14:00:00Z"),
                    fill("TSLA", Side.BUY, 1, "500", "2026-10-01T00:00:00Z"));       // October: not counted

            RecapPayload recap = recap(fills, Map.of());

            assertFalse(recap.quiet());
            assertEquals(dec("1300.00"), recap.moneyMoved().total());
            assertEquals(dec("59.09"), recap.moneyMoved().perMarketDay());
            assertEquals(22, recap.moneyMoved().marketDays());
            assertEquals(3, recap.moneyMoved().filledOrders());
            assertEquals(2, recap.moneyMoved().accounts());
            assertEquals(2, recap.instrumentsTraded());
        }

        @Test
        @DisplayName("places a fill in the UTC month it happened in, whatever its offset")
        void usesUtcMonths() {
            // 22:00 on 31 August in New York is 02:00 on 1 September UTC.
            RecapPayload recap = recap(List.of(fill("AAPL", Side.BUY, 1, "100", "2026-08-31T22:00:00-04:00")), Map.of());

            assertEquals(1, recap.moneyMoved().filledOrders());
        }
    }

    @Nested
    @DisplayName("top tickers")
    class TopTickers {

        @Test
        @DisplayName("ranks the five symbols with the most value traded, ties by symbol")
        void topFiveByValue() {
            List<RecapFill> fills = List.of(
                    fill("A", Side.BUY, 1, "10", "2026-09-02T14:00:00Z"),
                    fill("B", Side.BUY, 1, "60", "2026-09-02T14:00:00Z"),
                    fill("C", Side.BUY, 1, "30", "2026-09-02T14:00:00Z"),
                    fill("C", Side.SELL, 1, "30", "2026-09-03T14:00:00Z"),
                    fill("D", Side.BUY, 1, "40", "2026-09-02T14:00:00Z"),
                    fill("E", Side.BUY, 1, "20", "2026-09-02T14:00:00Z"),
                    fill("F", Side.BUY, 1, "20", "2026-09-02T14:00:00Z"));

            List<TickerActivity> top = recap(fills, Map.of()).topTickers();

            // B and C both traded 60, and E and F both 20: each tie goes to the earlier symbol.
            assertEquals(List.of("B", "C", "D", "E", "F"), top.stream().map(TickerActivity::symbol).toList());
            assertEquals(dec("60.00"), top.get(1).tradedValue());
            assertEquals(2, top.get(1).filledOrders());
        }

        @Test
        @DisplayName("carries each day's close through the month for the sparkline")
        void carriesCloses() {
            RecapPayload recap = recap(List.of(fill("AAPL", Side.BUY, 1, "200", "2026-09-02T14:00:00Z")),
                    Map.of("AAPL", closes(AUG_31, "200", LocalDate.of(2026, 9, 15), "205", SEP_30, "220")));

            List<BigDecimal> sparkline = recap.topTickers().get(0).closes();
            assertEquals(30, sparkline.size());
            assertEquals(dec("200.00"), sparkline.get(0));      // 1-14 Sep carry August's close
            assertEquals(dec("205.00"), sparkline.get(14));
            assertEquals(dec("220.00"), sparkline.get(29));
        }

        @Test
        @DisplayName("gives a symbol with no candles an empty sparkline")
        void noCloses() {
            RecapPayload recap = recap(List.of(fill("AAPL", Side.BUY, 1, "200", "2026-09-02T14:00:00Z")), Map.of());

            assertTrue(recap.topTickers().get(0).closes().isEmpty());
        }
    }

    @Nested
    @DisplayName("trading return")
    class TradingReturn {

        @Test
        @DisplayName("values units held from before the month at its start and end prices, even in a quiet month")
        void heldFromBefore() {
            Map<String, Map<LocalDate, BigDecimal>> prices = Map.of("AAPL", AAPL_PRICES,
                    RecapCalculator.BENCHMARK_SYMBOL, closes(AUG_31, "1000", SEP_30, "1030"));

            RecapPayload recap = recap(AAPL_HELD, prices);

            assertTrue(recap.quiet(), "No September fills, but the holding still moved");
            assertEquals(dec("0.00"), recap.moneyMoved().total());
            assertTrue(recap.topTickers().isEmpty());
            assertEquals(0, recap.instrumentsTraded());
            assertEquals(List.of(new SymbolReturn("AAPL", dec("10.00"), dec("200.00"))), recap.topGainers());
            assertEquals(dec("10.00"), recap.tradingReturn().returnPct());
            assertEquals(dec("7.00"), recap.benchmark().differencePts());
        }

        @Test
        @DisplayName("counts a position opened and closed in the month by what it bought and sold for")
        void roundTrip() {
            RecapPayload recap = recap(NVDA_ROUND_TRIP, Map.of());

            assertEquals(List.of(new SymbolReturn("NVDA", dec("20.00"), dec("100.00"))), recap.topGainers());
        }

        @Test
        @DisplayName("combines a starting position with buys and sells in the month")
        void startPlusTrades() {
            // Start 10 x 100 = 1,000. Buy 10 x 110 = 1,100. Sell 5 x 120 = 600. End 15 x 130 = 1,950.
            // Gain 1,950 + 600 - 1,000 - 1,100 = 450, on 2,100 put in: 21.43%.
            List<RecapFill> fills = List.of(
                    fill("MSFT", Side.BUY, 10, "90", "2026-08-20T14:00:00Z"),
                    fill("MSFT", Side.BUY, 10, "110", "2026-09-05T14:00:00Z"),
                    fill("MSFT", Side.SELL, 5, "120", "2026-09-15T14:00:00Z"));

            RecapPayload recap = recap(fills, Map.of("MSFT", closes(AUG_31, "100", SEP_30, "130")));

            assertEquals(List.of(new SymbolReturn("MSFT", dec("21.43"), dec("450.00"))), recap.topGainers());
            // 10 Sep: 20 units at August's close, 2,000, less 1,000 started with and 1,100 bought.
            assertEquals(dec("-100.00"), recap.tradingReturn().dailyPnl().get(9));
        }

        @Test
        @DisplayName("measures money sold and bought back once, on the most ever invested")
        void recycledMoney() {
            // 1,000 in, out at 1,100, 1,000 back in, out at 1,100: +200 on a peak of 1,000 is 20%.
            // Dividing by every buy (2,000) would understate it as 10%.
            List<RecapFill> fills = List.of(
                    fill("NVDA", Side.BUY, 10, "100", "2026-09-03T14:00:00Z"),
                    fill("NVDA", Side.SELL, 10, "110", "2026-09-04T14:00:00Z"),
                    fill("NVDA", Side.BUY, 10, "100", "2026-09-10T14:00:00Z"),
                    fill("NVDA", Side.SELL, 10, "110", "2026-09-11T14:00:00Z"));

            RecapPayload recap = recap(fills, Map.of());

            assertEquals(List.of(new SymbolReturn("NVDA", dec("20.00"), dec("200.00"))), recap.topGainers());
            assertEquals(dec("20.00"), recap.tradingReturn().returnPct());
        }

        @Test
        @DisplayName("values units bought before the symbol's first candle at their fill price until it has one")
        void boughtBeforeFirstCandle() {
            // Bought 10 at 50 on the 3rd; first close 60 on the 10th; 70 at the month's end.
            RecapPayload recap = recap(List.of(fill("NVDA", Side.BUY, 10, "50", "2026-09-03T14:00:00Z")),
                    Map.of("NVDA", closes(LocalDate.of(2026, 9, 10), "60", SEP_30, "70")));

            List<BigDecimal> daily = recap.tradingReturn().dailyPnl();
            assertEquals(dec("0.00"), daily.get(2));
            assertEquals(dec("0.00"), daily.get(8));
            assertEquals(dec("100.00"), daily.get(9));
            assertEquals(dec("200.00"), daily.get(29));
            assertEquals(dec("40.00"), recap.tradingReturn().returnPct());
        }

        @Test
        @DisplayName("weights each position by the value put in, rather than averaging returns")
        void weightedAcrossPositions() {
            // AAPL +200 on 2,000 and NVDA +100 on 500: 300 on 2,500 is 12%, not the 15% average.
            RecapPayload recap = recap(concat(AAPL_HELD, NVDA_ROUND_TRIP), Map.of("AAPL", AAPL_PRICES));

            assertEquals(dec("12.00"), recap.tradingReturn().returnPct());
            assertEquals(dec("300.00"), recap.tradingReturn().pnl());
        }

        @Test
        @DisplayName("charts the gain so far at the end of every day, ending on the month's gain")
        void dailyPnl() {
            List<BigDecimal> daily = recap(NVDA_ROUND_TRIP, Map.of()).tradingReturn().dailyPnl();

            assertEquals(30, daily.size());
            assertEquals(dec("0.00"), daily.get(0));
            // Held from the 3rd with no candles: valued at the price it was bought at.
            assertEquals(dec("0.00"), daily.get(2));
            assertEquals(dec("100.00"), daily.get(19));
            assertEquals(dec("100.00"), daily.get(29));
        }

        @Test
        @DisplayName("leaves out a symbol held at the month's end that has no price")
        void unpricedSymbol() {
            RecapPayload recap = recap(List.of(fill("GOOGL", Side.BUY, 5, "100", "2026-09-04T14:00:00Z")), Map.of());

            assertNull(recap.tradingReturn().returnPct());
            assertEquals(dec("0.00"), recap.tradingReturn().pnl());
            assertTrue(recap.topGainers().isEmpty());
            // Still traded, so still counted.
            assertEquals(dec("500.00"), recap.moneyMoved().total());
            assertEquals(1, recap.instrumentsTraded());
            assertEquals("GOOGL", recap.topTickers().get(0).symbol());
        }

        @Test
        @DisplayName("leaves out a symbol whose fills sell more than they bought")
        void incompleteHistory() {
            List<RecapFill> fills = List.of(
                    fill("AMZN", Side.BUY, 5, "100", "2026-08-04T14:00:00Z"),
                    fill("AMZN", Side.SELL, 10, "120", "2026-09-08T14:00:00Z"));

            RecapPayload recap = recap(fills, Map.of("AMZN", closes(AUG_31, "110", SEP_30, "130")));

            assertNull(recap.tradingReturn().returnPct());
            assertTrue(recap.topGainers().isEmpty() && recap.biggestLosers().isEmpty());
        }

        @Test
        @DisplayName("ignores a position closed out before the month began")
        void closedBefore() {
            List<RecapFill> fills = List.of(
                    fill("AMZN", Side.BUY, 5, "100", "2026-08-04T14:00:00Z"),
                    fill("AMZN", Side.SELL, 5, "120", "2026-08-08T14:00:00Z"));

            assertNull(recap(fills, Map.of()).tradingReturn().returnPct());
        }
    }

    @Nested
    @DisplayName("gainers and losers")
    class GainersAndLosers {

        @Test
        @DisplayName("splits symbols into gainers, best first, and losers, worst first, leaving out flat ones")
        void splitsAndSorts() {
            List<RecapFill> fills = concat(concat(AAPL_HELD, NVDA_ROUND_TRIP), List.of(
                    fill("TSLA", Side.BUY, 10, "100", "2026-09-02T14:00:00Z"),
                    fill("KO", Side.BUY, 1, "50", "2026-09-04T14:00:00Z")));

            RecapPayload recap = recap(fills, Map.of(
                    "AAPL", AAPL_PRICES,
                    "TSLA", closes(SEP_30, "80"),
                    "KO", closes(SEP_30, "50")));

            assertEquals(List.of("NVDA", "AAPL"), recap.topGainers().stream().map(SymbolReturn::symbol).toList());
            assertEquals(List.of(new SymbolReturn("TSLA", dec("-20.00"), dec("-200.00"))), recap.biggestLosers());
            assertEquals("NVDA", recap.topGainer().symbol());
            assertEquals(dec("20.00"), recap.topGainer().returnPct());
        }

        @Test
        @DisplayName("keeps the top five of each")
        void topFive() {
            List<RecapFill> fills = new ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                fills.add(fill("S" + i, Side.BUY, 1, "100", "2026-09-02T14:00:00Z"));
                fills.add(fill("S" + i, Side.SELL, 1, String.valueOf(100 + i), "2026-09-09T14:00:00Z"));
            }

            List<SymbolReturn> gainers = recap(fills, Map.of()).topGainers();

            assertEquals(List.of("S7", "S6", "S5", "S4", "S3"), gainers.stream().map(SymbolReturn::symbol).toList());
        }

        @Test
        @DisplayName("gives the top gainer its sparkline")
        void topGainerSparkline() {
            RecapPayload recap = recap(AAPL_HELD, Map.of("AAPL", AAPL_PRICES));

            assertEquals(30, recap.topGainer().closes().size());
            assertEquals(dec("220.00"), recap.topGainer().closes().get(29));
        }
    }

    @Nested
    @DisplayName("benchmark")
    class Benchmark {

        @Test
        @DisplayName("compares the trading return with the S&P 500's move over the month")
        void comparesWithSpx() {
            RecapPayload recap = recap(AAPL_HELD, Map.of("AAPL", AAPL_PRICES, "SPX", closes(AUG_31, "4000", SEP_30, "4084")));

            assertEquals("SPX", recap.benchmark().symbol());
            assertEquals("S&P 500", recap.benchmark().name());
            assertEquals(dec("2.10"), recap.benchmark().returnPct());
            assertEquals(dec("7.90"), recap.benchmark().differencePts());
        }

        @Test
        @DisplayName("still shows the index when the client has no trading return")
        void noTradingReturn() {
            RecapPayload recap = recap(List.of(), Map.of("SPX", closes(AUG_31, "4000", SEP_30, "4084")));

            assertEquals(dec("2.10"), recap.benchmark().returnPct());
            assertNull(recap.benchmark().differencePts());
        }

        @Test
        @DisplayName("is left out when the index has no prices")
        void noIndexPrices() {
            assertNull(recap(AAPL_HELD, Map.of("AAPL", AAPL_PRICES)).benchmark());
        }
    }

    @Nested
    @DisplayName("top sector")
    class TopSector {

        @Test
        @DisplayName("picks the sector with the most value traded, with its card label")
        void mostTraded() {
            // Tech: NVDA 1,100 + AAPL 200 = 1,300. Consumer: TSLA 1,000. ZZZZ has no sector.
            List<RecapFill> fills = concat(NVDA_ROUND_TRIP, List.of(
                    fill("AAPL", Side.BUY, 2, "100", "2026-09-10T14:00:00Z"),
                    fill("TSLA", Side.BUY, 10, "100", "2026-09-10T14:00:00Z"),
                    fill("ZZZZ", Side.BUY, 100, "100", "2026-09-10T14:00:00Z")));

            RecapPayload.TopSector top = recap(fills, Map.of()).topSector();

            assertEquals("Information Technology", top.sector());
            assertEquals("Tech", top.label());
            assertEquals(dec("1300.00"), top.tradedValue());
        }

        @Test
        @DisplayName("breaks a tie by sector name")
        void tie() {
            List<RecapFill> fills = List.of(
                    fill("NEE", Side.BUY, 1, "100", "2026-09-10T14:00:00Z"),
                    fill("XOM", Side.BUY, 1, "100", "2026-09-10T14:00:00Z"));

            assertEquals("Energy", recap(fills, Map.of()).topSector().sector());
        }

        @Test
        @DisplayName("is left out when no traded symbol has a sector")
        void none() {
            assertNull(recap(List.of(fill("ZZZZ", Side.BUY, 1, "100", "2026-09-10T14:00:00Z")), Map.of()).topSector());
        }
    }

    @Test
    @DisplayName("prices only the symbols traded in the month or held when it began")
    void symbolsToPrice() {
        List<RecapFill> fills = List.of(
                fill("AAPL", Side.BUY, 5, "100", "2026-08-03T14:00:00Z"),       // held into September
                fill("TSLA", Side.BUY, 5, "100", "2026-07-03T14:00:00Z"),
                fill("TSLA", Side.SELL, 5, "110", "2026-07-20T14:00:00Z"),      // closed out in July
                fill("NVDA", Side.BUY, 1, "50", "2026-09-03T14:00:00Z"),        // traded in September
                fill("XOM", Side.BUY, 1, "90", "2026-10-02T14:00:00Z"));        // October: too late

        assertEquals(java.util.Set.of("AAPL", "NVDA"), calculator.symbolsToPrice(SEP, fills));
    }

    @Test
    @DisplayName("recaps a month with no fills and nothing held as quiet, with zeros and no return")
    void quietMonth() {
        RecapPayload recap = calculator.calculate(SEP, List.of(), PriceBook.empty(), Map.of());

        assertTrue(recap.quiet());
        assertEquals(SEP, recap.month());
        assertEquals(dec("0.00"), recap.moneyMoved().total());
        assertEquals(dec("0.00"), recap.moneyMoved().perMarketDay());
        assertTrue(recap.topTickers().isEmpty());
        assertNull(recap.topGainer());
        assertNull(recap.tradingReturn().returnPct());
        assertEquals(30, recap.tradingReturn().dailyPnl().size());
        assertNull(recap.benchmark());
        assertNull(recap.topSector());
        assertEquals(0, recap.instrumentsTraded());
        assertNull(recap.topPercent(), "Rankings are only set once every client is known");
    }

    @Nested
    @DisplayName("top percents")
    class TopPercents {

        private Map<UUID, BigDecimal> clients(int count) {
            Map<UUID, BigDecimal> returns = new HashMap<>();
            for (int i = 1; i <= count; i++) {
                returns.put(new UUID(0, i), BigDecimal.valueOf(i));    // client i returned i%
            }
            return returns;
        }

        @Test
        @DisplayName("puts each client in the smallest top slice they are in, and stops at the top half")
        void ranks() {
            Map<UUID, Integer> top = calculator.topPercents(clients(25));

            assertEquals(4, top.get(new UUID(0, 25)));          // best of 25
            assertEquals(48, top.get(new UUID(0, 14)));         // 12th of 25
            assertFalse(top.containsKey(new UUID(0, 13)));      // 13th of 25 is the top 52%
            assertEquals(12, top.size());
        }

        @Test
        @DisplayName("gives tied clients the better rank")
        void ties() {
            Map<UUID, BigDecimal> returns = clients(10);
            returns.put(new UUID(0, 9), BigDecimal.valueOf(10));

            Map<UUID, Integer> top = calculator.topPercents(returns);

            assertEquals(10, top.get(new UUID(0, 10)));
            assertEquals(10, top.get(new UUID(0, 9)));
            assertEquals(30, top.get(new UUID(0, 8)));
        }

        @Test
        @DisplayName("neither ranks nor counts clients with no trading return")
        void skipsClientsWithoutReturns() {
            Map<UUID, BigDecimal> returns = clients(3);
            returns.put(new UUID(0, 99), null);

            Map<UUID, Integer> top = calculator.topPercents(returns);

            assertEquals(34, top.get(new UUID(0, 3)));          // best of 3, not of 4
            assertFalse(top.containsKey(new UUID(0, 99)));
        }

        @Test
        @DisplayName("is empty with no clients")
        void empty() {
            assertTrue(calculator.topPercents(Map.of()).isEmpty());
        }
    }

    @Test
    @DisplayName("withTopPercent sets the ranking and keeps everything else")
    void withTopPercent() {
        RecapPayload recap = recap(AAPL_HELD, Map.of("AAPL", AAPL_PRICES));

        RecapPayload ranked = recap.withTopPercent(4);

        assertEquals(4, ranked.topPercent());
        assertEquals(recap, ranked.withTopPercent(null));
    }

    @Test
    @DisplayName("counts a month's weekdays as its market days")
    void marketDays() {
        assertEquals(22, RecapCalculator.marketDays(SEP));
        assertEquals(20, RecapCalculator.marketDays(YearMonth.of(2026, 2)));
    }
}
