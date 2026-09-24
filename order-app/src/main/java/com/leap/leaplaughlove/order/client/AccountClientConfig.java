package com.leap.leaplaughlove.order.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configuration class for the AccountClient.
 * Provides a RestClient bean configured for communication with the account service.
 */
@Configuration
public class AccountClientConfig {

    /**
     * Default constructor for the configuration class.
     */
    protected AccountClientConfig() {
    }

    /**
     * Creates and configures a RestClient for communication with the account service.
     * @param builder the RestClient builder used to create the RestClient instance
     * @param baseUrl the base URL of the account service
     * @param connectTimeoutMs the connection timeout in milliseconds
     * @param readTimeoutMs the read timeout in milliseconds
     * @return a configured RestClient instance for communication with the account service
     */
    @Bean
    public RestClient accountRestClient(
            RestClient.Builder builder,
            @Value("${account.client.base-url:http://localhost:8082}") String baseUrl,
            @Value("${account.client.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${account.client.read-timeout-ms:2000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}

