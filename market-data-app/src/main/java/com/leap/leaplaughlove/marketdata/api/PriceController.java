package com.leap.leaplaughlove.marketdata.api;

import com.leap.leaplaughlove.marketdata.history.PriceCandle;
import com.leap.leaplaughlove.marketdata.history.PriceCandleRepository;
import com.leap.leaplaughlove.marketdata.simulation.MarketSimulationEngine;
import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Exposes the simulated latest prices and OHLC candle history for instruments.
 */
@RestController
@RequestMapping("/api/marketdata/prices")
public class PriceController {

    private static final int MAX_HISTORY_PAGE_SIZE = 1000;

    /**
     * Bucket widths a caller may request, in seconds: 1m, 5m, 1h, 1d. Restricted to the widths
     * the accumulator and backfill actually write, so an unsupported interval fails loudly
     * instead of returning an empty page that looks like "no history here".
     */
    private static final Set<Integer> SUPPORTED_INTERVALS = Set.of(60, 300, 3600, 86400);

    private final MarketSimulationEngine simulationEngine;
    private final PriceCandleRepository candleRepository;

    public PriceController(MarketSimulationEngine simulationEngine, PriceCandleRepository candleRepository) {
        this.simulationEngine = simulationEngine;
        this.candleRepository = candleRepository;
    }

    /**
     * Retrieves the latest simulated price for every active instrument.
     * @return the latest price for each active instrument
     */
    @GetMapping
    public List<PriceResponse> getLatestPrices() {
        return simulationEngine.latestAll().stream().map(this::toResponse).toList();
    }

    /**
     * Retrieves the latest simulated price for a single instrument symbol.
     * @param symbol the instrument symbol to look up
     * @return the latest price for the symbol
     * @throws ResponseStatusException with a 404 status if the symbol is unknown
     */
    @GetMapping("/{symbol}")
    public PriceResponse getLatestPrice(@PathVariable String symbol) {
        return simulationEngine.latest(symbol.toUpperCase())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown instrument symbol: " + symbol));
    }

    /**
     * Retrieves a paginated, newest-first page of OHLC candle history for an instrument
     * symbol within a time range.
     * @param symbol the instrument symbol to look up
     * @param from the start of the time range (defaults to one day before {@code to})
     * @param to the end of the time range (defaults to now)
     * @param interval the candle width, in seconds, to return: one of 60, 300, 3600 or 86400.
     *     Pick the width from the range being charted - a day of 60s candles is 1440 points,
     *     the same day at 300s is 288, which is what a chart can actually resolve
     * @param page the zero-based page number to retrieve
     * @param size the page size, between 1 and {@value #MAX_HISTORY_PAGE_SIZE}
     * @return the requested page of candle history
     * @throws ResponseStatusException with a 400 status if the paging or range parameters
     *     are invalid
     */
    @GetMapping("/{symbol}/history")
    public Page<PriceCandleResponse> getHistory(
            @PathVariable String symbol,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "60") int interval,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must not be negative");
        }
        if (size < 1 || size > MAX_HISTORY_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "size must be between 1 and " + MAX_HISTORY_PAGE_SIZE);
        }
        if (!SUPPORTED_INTERVALS.contains(interval)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "interval must be one of " + SUPPORTED_INTERVALS.stream().sorted().toList());
        }
        OffsetDateTime rangeTo = to != null ? to : OffsetDateTime.now();
        OffsetDateTime rangeFrom = from != null ? from : rangeTo.minusDays(1);
        if (rangeFrom.isAfter(rangeTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        return candleRepository
                .findByInstrument_SymbolAndBucketSecondsAndBucketStartBetweenOrderByBucketStartDesc(
                        symbol.toUpperCase(), interval, rangeFrom, rangeTo, PageRequest.of(page, size))
                .map(this::toResponse);
    }

    private PriceResponse toResponse(PriceState state) {
        return new PriceResponse(state.symbol(), state.price(), state.asOf());
    }

    private PriceCandleResponse toResponse(PriceCandle candle) {
        return new PriceCandleResponse(
                candle.getBucketStart(), candle.getBucketSeconds(),
                candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose());
    }
}
