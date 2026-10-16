package com.leap.leaplaughlove.reporting.recap;

import com.leap.leaplaughlove.reporting.recap.RecapPayload.Benchmark;
import com.leap.leaplaughlove.reporting.recap.RecapPayload.MoneyMoved;
import com.leap.leaplaughlove.reporting.recap.RecapPayload.SymbolReturn;
import com.leap.leaplaughlove.reporting.recap.RecapPayload.TickerActivity;
import com.leap.leaplaughlove.reporting.recap.RecapPayload.TopGainer;
import com.leap.leaplaughlove.reporting.recap.RecapPayload.TopSector;
import com.leap.leaplaughlove.reporting.recap.RecapPayload.TradingReturn;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Works out a client's monthly recap from their fills and daily closing prices. Pure: no database
 * or network, so every figure is tested on its own.
 *
 * <p>Each symbol's trading return for the month is
 * <pre>
 *   gain     = (units held at month end x end price + money from sells)
 *            - (units held at month start x start price + money spent on buys)
 *   invested = start value + money spent on buys so far - money from sells so far
 *   return   = gain / the most invested at any point in the month
 * </pre>
 * Dividing by the peak rather than by every buy keeps money that is sold and bought back from
 * counting twice, so a client who trades the same 1,000 in and out ten times is measured on 1,000,
 * not 10,000. The client's trading return is the total gain over the total of those peaks, so a
 * large position counts for more than a small one. A symbol is left out of every return when it can't be
 * priced, or when the client's fills sell more units than they bought: the reporting schema then
 * doesn't hold the whole history, and any return would be made up. Such symbols still count towards
 * money moved, top tickers, instruments traded and sector.
 */
@Component
public class RecapCalculator {

    /** The version of {@link RecapPayload}'s JSON shape this calculator writes. */
    public static final int SCHEMA_VERSION = 1;

    /** How many symbols the top tickers, gainers and losers lists hold. */
    static final int TOP_COUNT = 5;

    /** The index a client's trading return is compared with. */
    static final String BENCHMARK_SYMBOL = "SPX";
    static final String BENCHMARK_NAME = "S&P 500";

    /** Rankings are only shown to clients in this top percentage of the month's clients. */
    static final int TOP_PERCENT_SHOWN_UP_TO = 50;

    /** The short sector names the recap card has room for, by GICS sector. */
    static final Map<String, String> SECTOR_LABELS = Map.ofEntries(
            Map.entry("Communication Services", "Comms"),
            Map.entry("Consumer Discretionary", "Consumer"),
            Map.entry("Consumer Staples", "Staples"),
            Map.entry("Energy", "Energy"),
            Map.entry("Financials", "Finance"),
            Map.entry("Health Care", "Health"),
            Map.entry("Industrials", "Industrials"),
            Map.entry("Information Technology", "Tech"),
            Map.entry("Materials", "Materials"),
            Map.entry("Real Estate", "Real Estate"),
            Map.entry("Utilities", "Utilities"),
            Map.entry("Crypto", "Crypto"));

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * Works out one client's recap. The ranking is left unset; {@link #topPercents} fills it in once
     * every client's recap for the month is known.
     * @param month the month to recap
     * @param fills the client's fills up to the end of the month, in any order; earlier months' fills
     *              give the units held when the month starts, later ones are ignored
     * @param prices daily closes for every symbol the client held or traded, and for the benchmark,
     *               from the last day of the month before through the month's last day
     * @param sectors GICS sector by trading symbol
     * @return the recap, without a ranking
     */
    public RecapPayload calculate(YearMonth month, List<RecapFill> fills, PriceBook prices,
                                  Map<String, String> sectors) {
        OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<RecapFill> history = fills.stream()
                .filter(fill -> fill.filledAt().isBefore(end))
                .sorted(Comparator.comparing(RecapFill::filledAt))
                .toList();
        List<RecapFill> inMonth = history.stream().filter(fill -> !fill.filledAt().isBefore(start)).toList();

        List<Position> positions = positions(month, history, start, prices);
        List<SymbolReturn> returns = positions.stream()
                .map(position -> new SymbolReturn(position.symbol, percent(position.gain(), position.putIn()),
                        money(position.gain())))
                .toList();
        List<SymbolReturn> gainers = returns.stream()
                .filter(r -> r.returnPct().signum() > 0)
                .sorted(Comparator.comparing(SymbolReturn::returnPct).reversed().thenComparing(SymbolReturn::symbol))
                .limit(TOP_COUNT)
                .toList();
        List<SymbolReturn> losers = returns.stream()
                .filter(r -> r.returnPct().signum() < 0)
                .sorted(Comparator.comparing(SymbolReturn::returnPct).thenComparing(SymbolReturn::symbol))
                .limit(TOP_COUNT)
                .toList();
        TopGainer topGainer = gainers.isEmpty() ? null : new TopGainer(gainers.get(0).symbol(),
                gainers.get(0).returnPct(), gainers.get(0).pnl(), closes(month, gainers.get(0).symbol(), prices));

        TradingReturn tradingReturn = tradingReturn(month, positions);
        return new RecapPayload(
                month,
                inMonth.isEmpty(),
                moneyMoved(month, inMonth),
                topTickers(month, inMonth, prices),
                topGainer,
                gainers,
                losers,
                tradingReturn,
                benchmark(month, prices, tradingReturn.returnPct()),
                (int) inMonth.stream().map(RecapFill::symbol).distinct().count(),
                topSector(inMonth, sectors),
                null);
    }

    /**
     * Ranks the month's clients by trading return. A client's top percent is the smallest top slice
     * they are in: the best of 25 clients is in the top 4%. Tied clients share the better rank.
     * Clients with no trading return aren't ranked and don't count towards anyone's slice.
     * @param tradingReturns each client's trading return, null when they have none
     * @return the top percent of each client in the top {@value #TOP_PERCENT_SHOWN_UP_TO}%; clients
     *         below that are left out, so the card never boasts about being in the top 90%
     */
    public Map<UUID, Integer> topPercents(Map<UUID, BigDecimal> tradingReturns) {
        List<Map.Entry<UUID, BigDecimal>> ranked = tradingReturns.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed())
                .toList();
        Map<UUID, Integer> topPercents = new HashMap<>();
        int rank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            // 1 + the number of clients who did strictly better, so ties share the better rank.
            if (i == 0 || ranked.get(i).getValue().compareTo(ranked.get(i - 1).getValue()) < 0) {
                rank = i + 1;
            }
            int topPercent = (int) Math.ceilDiv(rank * 100L, ranked.size());
            if (topPercent > TOP_PERCENT_SHOWN_UP_TO) {
                break;
            }
            topPercents.put(ranked.get(i).getKey(), topPercent);
        }
        return topPercents;
    }

    /**
     * The symbols a client's recap needs prices for: those traded in the month, and those held when
     * it began. Symbols closed out earlier are left out, so a long history doesn't mean fetching
     * prices nobody needs.
     * @param month the month to recap
     * @param fills the client's fills, in any order
     * @return the trading symbols to price
     */
    public Set<String> symbolsToPrice(YearMonth month, Collection<RecapFill> fills) {
        OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        Map<String, Long> unitsAtStart = new HashMap<>();
        Set<String> symbols = new TreeSet<>();
        for (RecapFill fill : fills) {
            if (fill.filledAt().isBefore(start)) {
                unitsAtStart.merge(fill.symbol(), fill.signedQuantity(), Long::sum);
            } else if (fill.filledAt().isBefore(end)) {
                symbols.add(fill.symbol());
            }
        }
        unitsAtStart.forEach((symbol, units) -> {
            if (units > 0) {
                symbols.add(symbol);
            }
        });
        return symbols;
    }

    private static MoneyMoved moneyMoved(YearMonth month, List<RecapFill> inMonth) {
        BigDecimal total = sum(inMonth, RecapFill::value);
        int marketDays = marketDays(month);
        return new MoneyMoved(money(total), total.divide(BigDecimal.valueOf(marketDays), 2, RoundingMode.HALF_UP),
                marketDays, inMonth.size(), (int) inMonth.stream().map(RecapFill::accountId).distinct().count());
    }

    private static List<TickerActivity> topTickers(YearMonth month, List<RecapFill> inMonth, PriceBook prices) {
        Map<String, List<RecapFill>> bySymbol = inMonth.stream().collect(Collectors.groupingBy(RecapFill::symbol));
        return bySymbol.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), sum(entry.getValue(), RecapFill::value)))
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_COUNT)
                .map(entry -> new TickerActivity(entry.getKey(), money(entry.getValue()),
                        bySymbol.get(entry.getKey()).size(), closes(month, entry.getKey(), prices)))
                .toList();
    }

    private static TradingReturn tradingReturn(YearMonth month, List<Position> positions) {
        BigDecimal gain = sum(positions, Position::gain);
        BigDecimal putIn = sum(positions, Position::putIn);
        List<BigDecimal> dailyPnl = new ArrayList<>();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            dailyPnl.add(money(sum(positions, position -> position.gainBy(date))));
        }
        return new TradingReturn(positions.isEmpty() ? null : percent(gain, putIn), money(gain), dailyPnl);
    }

    private static Benchmark benchmark(YearMonth month, PriceBook prices, BigDecimal tradingReturnPct) {
        Optional<BigDecimal> startPrice = prices.closeOn(BENCHMARK_SYMBOL, month.atDay(1).minusDays(1));
        Optional<BigDecimal> endPrice = prices.closeOn(BENCHMARK_SYMBOL, month.atEndOfMonth());
        if (startPrice.isEmpty() || endPrice.isEmpty() || startPrice.get().signum() == 0) {
            return null;
        }
        BigDecimal returnPct = percent(endPrice.get().subtract(startPrice.get()), startPrice.get());
        return new Benchmark(BENCHMARK_SYMBOL, BENCHMARK_NAME, returnPct,
                tradingReturnPct == null ? null : tradingReturnPct.subtract(returnPct));
    }

    private static TopSector topSector(List<RecapFill> inMonth, Map<String, String> sectors) {
        Map<String, BigDecimal> bySector = new TreeMap<>();
        for (RecapFill fill : inMonth) {
            String sector = sectors.get(fill.symbol());
            if (sector != null) {
                bySector.merge(sector, fill.value(), BigDecimal::add);
            }
        }
        return bySector.entrySet().stream()
                // The TreeMap's name order breaks ties, since max keeps the first of equals.
                .max(Map.Entry.comparingByValue())
                .map(entry -> new TopSector(entry.getKey(), SECTOR_LABELS.getOrDefault(entry.getKey(), entry.getKey()),
                        money(entry.getValue())))
                .orElse(null);
    }

    /**
     * Every symbol the client held at the month's start or traded in it, with what it takes to
     * value it, leaving out the ones no return can honestly be given for.
     */
    private static List<Position> positions(YearMonth month, List<RecapFill> history, OffsetDateTime start,
                                            PriceBook prices) {
        Map<String, List<RecapFill>> bySymbol = new LinkedHashMap<>();
        history.forEach(fill -> bySymbol.computeIfAbsent(fill.symbol(), symbol -> new ArrayList<>()).add(fill));

        LocalDate dayBefore = month.atDay(1).minusDays(1);
        List<Position> positions = new ArrayList<>();
        bySymbol.forEach((symbol, fills) -> {
            long units = 0;
            long startUnits = 0;
            List<RecapFill> inMonth = new ArrayList<>();
            for (RecapFill fill : fills) {
                units += fill.signedQuantity();
                if (units < 0) {
                    return;     // sold more than it bought: the history is incomplete
                }
                if (fill.filledAt().isBefore(start)) {
                    startUnits = units;
                } else {
                    inMonth.add(fill);
                }
            }
            if (startUnits == 0 && inMonth.isEmpty()) {
                return;         // closed out before the month began
            }
            Optional<BigDecimal> startPrice = prices.closeOn(symbol, dayBefore);
            Optional<BigDecimal> endPrice = prices.closeOn(symbol, month.atEndOfMonth());
            if ((startUnits > 0 && startPrice.isEmpty()) || (units > 0 && endPrice.isEmpty())) {
                return;         // can't be valued
            }
            Position position = new Position(symbol, startUnits, startPrice.orElse(BigDecimal.ZERO), inMonth, prices,
                    month.atEndOfMonth());
            if (position.putIn().signum() > 0) {
                positions.add(position);
            }
        });
        return positions;
    }

    /** One symbol's position through the month. */
    private static final class Position {
        private final String symbol;
        private final long startUnits;
        private final BigDecimal startValue;
        private final BigDecimal peakInvested;
        private final List<RecapFill> inMonth;
        private final PriceBook prices;
        private final LocalDate monthEnd;

        Position(String symbol, long startUnits, BigDecimal startPrice, List<RecapFill> inMonth, PriceBook prices,
                 LocalDate monthEnd) {
            this.symbol = symbol;
            this.startUnits = startUnits;
            this.startValue = startPrice.multiply(BigDecimal.valueOf(startUnits));
            this.inMonth = inMonth;
            this.prices = prices;
            this.monthEnd = monthEnd;
            BigDecimal invested = startValue;
            BigDecimal peak = startValue;
            for (RecapFill fill : inMonth) {
                invested = fill.side() == RecapFill.Side.BUY ? invested.add(fill.value()) : invested.subtract(fill.value());
                peak = peak.max(invested);
            }
            this.peakInvested = peak;
        }

        /**
         * The most money in the position at any point in the month: the start value, plus buys,
         * less sells, at its highest. Buying back with money a sell freed up adds nothing.
         */
        BigDecimal putIn() {
            return peakInvested;
        }

        /** The gain at the end of the month. */
        BigDecimal gain() {
            return gainBy(monthEnd);
        }

        /**
         * The gain at the end of a day: the units then held at that day's close, plus the money from
         * sells so far, less the start value and the money spent on buys so far.
         */
        BigDecimal gainBy(LocalDate day) {
            List<RecapFill> soFar = inMonth.stream().filter(fill -> !fillDay(fill).isAfter(day)).toList();
            long units = startUnits + soFar.stream().mapToLong(RecapFill::signedQuantity).sum();
            BigDecimal held = units == 0 ? BigDecimal.ZERO : priceOn(day, soFar).multiply(BigDecimal.valueOf(units));
            return held
                    .add(sum(side(soFar, RecapFill.Side.SELL), RecapFill::value))
                    .subtract(startValue)
                    .subtract(sum(side(soFar, RecapFill.Side.BUY), RecapFill::value));
        }

        /**
         * The day's close. Units held at the month's start always have one, as the start price
         * exists; units bought in the month before the symbol's first candle fall back to the price
         * of the latest fill.
         */
        private BigDecimal priceOn(LocalDate day, List<RecapFill> soFar) {
            return prices.closeOn(symbol, day).orElseGet(() -> soFar.get(soFar.size() - 1).price());
        }

        private static List<RecapFill> side(List<RecapFill> fills, RecapFill.Side side) {
            return fills.stream().filter(fill -> fill.side() == side).toList();
        }
    }

    /**
     * The symbol's price at the end of each day of the month, oldest first, starting from its first
     * candle when that falls inside the month.
     */
    private static List<BigDecimal> closes(YearMonth month, String symbol, PriceBook prices) {
        List<BigDecimal> closes = new ArrayList<>();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            prices.closeOn(symbol, month.atDay(day)).ifPresent(close -> closes.add(money(close)));
        }
        return closes;
    }

    private static LocalDate fillDay(RecapFill fill) {
        return fill.filledAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    /** The month's weekdays, which the "per market day" figure is spread over. */
    static int marketDays(YearMonth month) {
        int days = 0;
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            DayOfWeek weekday = month.atDay(day).getDayOfWeek();
            if (weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }

    private static <T> BigDecimal sum(List<T> items, Function<T, BigDecimal> amount) {
        return items.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** part as a percentage of whole, to two places. */
    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
