package com.leap.leaplaughlove.order.quote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Supplies the current market quote an order is priced against at execution time, rejecting
 * quotes that are too old to reflect the market.
 */
@Service
public class CurrentQuoteService {

    private static final Logger log = LoggerFactory.getLogger(CurrentQuoteService.class);

    private final CurrentQuoteClient quoteClient;
    private final long maxQuoteAgeSeconds;

    /**
     * Constructs a CurrentQuoteService with the specified quote client and maximum quote age.
     * @param quoteClient the client used to fetch the latest quotes
     * @param maxQuoteAgeSeconds the maximum age of a quote in seconds before it is considered stale
     */
    public CurrentQuoteService(CurrentQuoteClient quoteClient,
                               @Value("${trading.execution.max-quote-age-seconds:5}") long maxQuoteAgeSeconds) {
        this.quoteClient = quoteClient;
        this.maxQuoteAgeSeconds = maxQuoteAgeSeconds;
    }

    /**
     * Retrieves the current quote for the specified symbol, ensuring it is not stale.
     * @param symbol the instrument symbol for which to fetch the current quote
     * @return the latest QuoteSnapshot for the specified symbol
     * @throws IllegalArgumentException if the symbol is null or blank
     * @throws QuoteUnavailableException if no current quote is available for the symbol
     * @throws StaleQuoteException if the retrieved quote is older than the maximum allowed age
     */
    public QuoteSnapshot getCurrentQuote(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }

        QuoteSnapshot quote = quoteClient.fetchLatest(symbol)
                .orElseThrow(() -> new QuoteUnavailableException(
                        "No current quote is available for symbol " + symbol));

        OffsetDateTime staleBefore = OffsetDateTime.now().minusSeconds(maxQuoteAgeSeconds);
        if (quote.quoteTimestamp().isBefore(staleBefore)) {
            log.warn("Rejected quote for {}: quoteTimestamp {} is older than {} seconds",
                    symbol, quote.quoteTimestamp(), maxQuoteAgeSeconds);
            throw new StaleQuoteException("Current quote for symbol " + symbol
                    + " is stale: quoteTimestamp " + quote.quoteTimestamp()
                    + " is older than the maximum quote age of " + maxQuoteAgeSeconds + " seconds");
        }

        return quote;
    }
}

