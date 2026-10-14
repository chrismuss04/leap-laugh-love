package com.leap.leaplaughlove.order.quote;

import com.leap.leaplaughlove.common.marketdata.MarketDataPriceClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches candle history and latest prices from the market data service. Failures surface as
 * {@link QuoteUnavailableException}.
 */
@Component
public class PriceHistoryClient extends MarketDataPriceClient {

    /**
     * Creates a new PriceHistoryClient.
     * @param marketDataRestClient the RestClient configured for the market data service
     */
    public PriceHistoryClient(RestClient marketDataRestClient) {
        super(marketDataRestClient);
    }

    @Override
    protected RuntimeException quoteUnavailable(String message, Throwable cause) {
        return new QuoteUnavailableException(message, cause);
    }
}
