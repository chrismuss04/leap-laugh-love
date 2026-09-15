package com.leap.leaplaughlove.trading.quote;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configures the HTTP client trading-app uses to reach the market data service.
 */
@Configuration
public class MarketDataClientConfig {

    /**
     * Constructs a new MarketDataClientConfig instance.
     */
    protected MarketDataClientConfig() {
    }

    /**
     * Creates the RestClient used to call the market data service. Timeouts are deliberately
     * short: a quote fetched after a long stall would be stale by the time it arrived, so
     * failing fast is preferable to waiting.
     * @param builder the auto-configured RestClient builder
     * @param baseUrl the base URL of the market data service
     * @param connectTimeoutMs how long to wait to establish a connection before failing
     * @param readTimeoutMs how long to wait for a response before failing
     * @return the RestClient pointed at the market data service
     */
    @Bean
    public RestClient marketDataRestClient(
            RestClient.Builder builder,
            @Value("${marketdata.client.base-url:http://localhost:8083}") String baseUrl,
            @Value("${marketdata.client.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${marketdata.client.read-timeout-ms:2000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
