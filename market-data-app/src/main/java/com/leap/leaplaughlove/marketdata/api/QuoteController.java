package com.leap.leaplaughlove.marketdata.api;

import com.leap.leaplaughlove.marketdata.ingestion.QuoteIngestionService;
import com.leap.leaplaughlove.marketdata.ingestion.QuoteState;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Exposes the quotes accepted by the market quote ingestion pipeline.
 */
@RestController
@RequestMapping("/api/marketdata/quotes")
public class QuoteController {

    private final QuoteIngestionService quoteIngestionService;

    public QuoteController(QuoteIngestionService quoteIngestionService) {
        this.quoteIngestionService = quoteIngestionService;
    }

    /**
     * Retrieves the latest quote for every instrument seen by the ingestion pipeline.
     * @return the latest quote for each instrument
     */
    @GetMapping
    public List<QuoteResponse> getLatestQuotes() {
        return quoteIngestionService.latestAll().stream().map(this::toResponse).toList();
    }

    /**
     * Retrieves the latest quote for a single instrument symbol.
     * @param symbol the instrument symbol to look up
     * @return the latest quote for the symbol
     * @throws ResponseStatusException with a 404 status if no quote has been ingested for
     *     the symbol
     */
    @GetMapping("/{symbol}")
    public QuoteResponse getLatestQuote(@PathVariable String symbol) {
        return quoteIngestionService.latest(symbol.toUpperCase())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown instrument symbol: " + symbol));
    }

    private QuoteResponse toResponse(QuoteState state) {
        return new QuoteResponse(state.symbol(), state.bidPrice(), state.bidSize(),
                state.askPrice(), state.askSize(), state.lastPrice(), state.lastSize(),
                state.exchange(), state.quoteTimestamp());
    }
}
