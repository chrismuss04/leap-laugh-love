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

    public MarketDataClientConfig() {
    }

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

