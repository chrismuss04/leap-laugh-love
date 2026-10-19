package com.leap.leaplaughlove.reporting.recap;

import com.leap.leaplaughlove.common.marketdata.MarketDataPriceClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches candle history from the market data service for recaps. Failures surface as
 * {@link RecapPricesUnavailableException}.
 */
@Component
public class RecapPriceClient extends MarketDataPriceClient {

    /**
     * Creates a new RecapPriceClient.
     * @param marketDataRestClient the RestClient configured for the market data service
     */
    public RecapPriceClient(RestClient marketDataRestClient) {
        super(marketDataRestClient);
    }

    @Override
    protected RuntimeException quoteUnavailable(String message, Throwable cause) {
        return new RecapPricesUnavailableException(message, cause);
    }
}
