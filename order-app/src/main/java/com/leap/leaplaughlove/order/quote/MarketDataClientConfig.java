package com.leap.leaplaughlove.order.quote;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configures the HTTP client order-app uses to reach the market data service.
 */
@Configuration
public class MarketDataClientConfig {

    /**
     * Protected config constructor for JPA and Spring framework usage.
     */
    protected MarketDataClientConfig() {
    }

    /**
     * Configures and provides a RestClient for accessing the market data service.
     * @param builder the RestClient builder used to configure the client
     * @param baseUrl the base URL of the market data service
     * @param connectTimeoutMs the connection timeout in milliseconds
     * @param readTimeoutMs the read timeout in milliseconds
     * @return a configured RestClient instance for accessing the market data service
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

