package com.leap.leaplaughlove.account.quote;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configures the HTTP client account-app uses to reach the market data service.
 */
@Configuration
public class MarketDataClientConfig {

    /**
     * Construct a new MarketDataClientConfig
     */
    public MarketDataClientConfig() {
    }

    /**
     * Create a RestClient for the market data service.
     * 
     * @param builder          The RestClient builder.
     * @param baseUrl          The base URL for the market data service.
     * @param connectTimeoutMs The connection timeout in milliseconds.
     * @param readTimeoutMs    The read timeout in milliseconds.
     * @return A RestClient for the market data service.
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
