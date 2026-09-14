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

@RestController
@RequestMapping("/api/marketdata/quotes")
public class QuoteController {

    private final QuoteIngestionService quoteIngestionService;

    public QuoteController(QuoteIngestionService quoteIngestionService) {
        this.quoteIngestionService = quoteIngestionService;
    }

    @GetMapping
    public List<QuoteResponse> getLatestQuotes() {
        return quoteIngestionService.latestAll().stream().map(this::toResponse).toList();
    }

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
