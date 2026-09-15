package com.leap.leaplaughlove.trading.quote;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Fetches the latest quote for an instrument from the market data service. The market data
 * quote endpoint is JWT-secured, so the caller's own bearer token is forwarded on the
 * outbound call rather than using a separate service credential.
 */
@Component
public class CurrentQuoteClient {

    private final RestClient restClient;

    /**
     * Creates a new CurrentQuoteClient.
     * @param marketDataRestClient the RestClient pointed at the market data service
     */
    public CurrentQuoteClient(RestClient marketDataRestClient) {
        this.restClient = marketDataRestClient;
    }

    /**
     * Fetches the latest quote the market data service holds for the given symbol. The quote
     * is returned as published; judging whether it is fresh enough to trade on is the
     * caller's responsibility.
     * @param symbol the instrument symbol to look up
     * @return the latest quote for the symbol, or empty if the market data service holds none
     * @throws QuoteUnavailableException if the market data service could not be reached or
     *     returned an error
     */
    public Optional<QuoteSnapshot> fetchLatest(String symbol) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/api/marketdata/quotes/{symbol}", symbol)
                    .headers(this::forwardCallerToken)
                    .retrieve()
                    .body(QuoteSnapshot.class));
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (RestClientException ex) {
            throw new QuoteUnavailableException(
                    "Failed to fetch current quote for symbol " + symbol, ex);
        }
    }

    private void forwardCallerToken(HttpHeaders headers) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
        }
    }
}
