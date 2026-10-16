package com.leap.leaplaughlove.reporting.recap;

import com.leap.leaplaughlove.common.marketdata.MarketDataPriceClient.CandleClose;
import com.leap.leaplaughlove.common.security.JwtService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Loads the daily closes a month's recaps are valued at, from the market data service. Each symbol
 * is fetched once however many clients hold it, along with the benchmark index.
 *
 * <p>Runs outside any request (the monthly job) as well as inside one, so it never forwards the
 * caller's token: it signs a market-history service token, which market data accepts on its history
 * endpoint only. Those tokens live for a minute, so each symbol gets a fresh one rather than a whole
 * batch sharing one that could expire halfway through.
 */
@Component
public class RecapPrices {

    /** One daily candle, in seconds. */
    static final int DAILY = 86_400;

    /** The subject of the service token: a batch acts for no one client. */
    private static final UUID NO_CLIENT = new UUID(0L, 0L);

    private final RecapPriceClient client;
    private final JwtService jwtService;

    /**
     * Creates the loader.
     * @param client the market data history client
     * @param jwtService signs the market-history service tokens
     */
    public RecapPrices(RecapPriceClient client, JwtService jwtService) {
        this.client = client;
        this.jwtService = jwtService;
    }

    /**
     * Fetches the daily closes for the symbols and the benchmark, from the last day of the month
     * before, whose close the month starts from, through the month's last day.
     * @param month the month being recapped
     * @param symbols trading symbols, as reporting.orders holds them
     * @return the closes, keyed by trading symbol; a symbol market data doesn't know has none
     * @throws RecapPricesUnavailableException when the market data service fails
     */
    public PriceBook load(YearMonth month, Collection<String> symbols) {
        OffsetDateTime from = month.atDay(1).minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        TreeSet<String> wanted = new TreeSet<>(symbols);
        wanted.add(RecapCalculator.BENCHMARK_SYMBOL);

        Map<String, Map<LocalDate, BigDecimal>> closes = new HashMap<>();
        for (String symbol : wanted) {
            Map<LocalDate, BigDecimal> byDay = new HashMap<>();
            for (CandleClose candle : client.fetchCloses(marketDataSymbol(symbol), from, to, DAILY,
                    jwtService.generateHistoryToken(NO_CLIENT))) {
                byDay.put(candle.bucketStart().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(), candle.close());
            }
            if (!byDay.isEmpty()) {
                closes.put(symbol, byDay);
            }
        }
        return new PriceBook(closes);
    }

    /**
     * The market data service's name for a trading symbol: the same, less any "/", which can't
     * appear in a URL path segment (BTC/USD trades as BTCUSD there).
     * @param tradingSymbol the symbol reporting.orders holds
     * @return the market data symbol
     */
    static String marketDataSymbol(String tradingSymbol) {
        return tradingSymbol.replace("/", "");
    }
}
