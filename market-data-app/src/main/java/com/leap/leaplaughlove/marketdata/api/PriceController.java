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

@RestController
@RequestMapping("/api/marketdata/prices")
public class PriceController {

    private static final int MAX_HISTORY_PAGE_SIZE = 500;

    private final MarketSimulationEngine simulationEngine;
    private final PriceCandleRepository candleRepository;

    public PriceController(MarketSimulationEngine simulationEngine, PriceCandleRepository candleRepository) {
        this.simulationEngine = simulationEngine;
        this.candleRepository = candleRepository;
    }

    @GetMapping
    public List<PriceResponse> getLatestPrices() {
        return simulationEngine.latestAll().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{symbol}")
    public PriceResponse getLatestPrice(@PathVariable String symbol) {
        return simulationEngine.latest(symbol.toUpperCase())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown instrument symbol: " + symbol));
    }

    @GetMapping("/{symbol}/history")
    public Page<PriceCandleResponse> getHistory(
            @PathVariable String symbol,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must not be negative");
        }
        if (size < 1 || size > MAX_HISTORY_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "size must be between 1 and " + MAX_HISTORY_PAGE_SIZE);
        }
        OffsetDateTime rangeTo = to != null ? to : OffsetDateTime.now();
        OffsetDateTime rangeFrom = from != null ? from : rangeTo.minusDays(1);
        if (rangeFrom.isAfter(rangeTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        return candleRepository
                .findByInstrument_SymbolAndBucketStartBetweenOrderByBucketStartDesc(
                        symbol.toUpperCase(), rangeFrom, rangeTo, PageRequest.of(page, size))
                .map(this::toResponse);
    }

    private PriceResponse toResponse(PriceState state) {
        return new PriceResponse(state.symbol(), state.price(), state.asOf());
    }

    private PriceCandleResponse toResponse(PriceCandle candle) {
        return new PriceCandleResponse(
                candle.getBucketStart(), candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose());
    }
}
