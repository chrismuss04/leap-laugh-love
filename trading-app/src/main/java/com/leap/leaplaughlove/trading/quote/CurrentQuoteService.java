package com.leap.leaplaughlove.trading.quote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Supplies the current market quote an order is priced against at execution time, rejecting
 * quotes that are too old to reflect the market. The market data service already drops stale
 * quotes on ingestion, but a quote can also go stale while sitting as the latest published
 * one (for example if the feed stops), so freshness is checked again here at the point of use.
 */
@Service
public class CurrentQuoteService {

    private static final Logger log = LoggerFactory.getLogger(CurrentQuoteService.class);

    private final CurrentQuoteClient quoteClient;
    private final long maxQuoteAgeSeconds;

    /**
     * Creates a new CurrentQuoteService.
     * @param quoteClient the client used to fetch quotes from the market data service
     * @param maxQuoteAgeSeconds how old a quote's timestamp may be before it is rejected as
     *     too stale to price an order against
     */
    public CurrentQuoteService(CurrentQuoteClient quoteClient,
                               @Value("${trading.execution.max-quote-age-seconds:5}") long maxQuoteAgeSeconds) {
        this.quoteClient = quoteClient;
        this.maxQuoteAgeSeconds = maxQuoteAgeSeconds;
    }

    /**
     * Gets the current, non-stale quote for the given instrument symbol.
     * @param symbol the instrument symbol to price against
     * @return the current quote for the symbol
     * @throws IllegalArgumentException if the symbol is null or blank
     * @throws QuoteUnavailableException if no quote is available for the symbol
     * @throws StaleQuoteException if the available quote is older than the configured maximum
     *     quote age
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
