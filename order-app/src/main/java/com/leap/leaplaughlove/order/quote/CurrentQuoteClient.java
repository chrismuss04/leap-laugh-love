package com.leap.leaplaughlove.order.quote;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

/**
 * HTTP Client that fetches the latest quote for an instrument from the market data service with caller JWT token forwarded.
 */
@Component
public class CurrentQuoteClient {

    private final RestClient restClient;
    /**
     * Constructs a new CurrentQuoteClient with the given market data REST client.
     * @param marketDataRestClient the REST client pointed at the market data service
     */
    public CurrentQuoteClient(RestClient marketDataRestClient) {
        this.restClient = marketDataRestClient;
    }

    /**
     * Fetches the latest quote for the given instrument symbol. Returns an empty Optional if the quote is not found.
     * Hits the market data service endpoint to retrieve the latest quote for the specified instrument symbol.
     * @param symbol the instrument symbol for which to fetch the latest quote
     * @return an Optional containing the latest QuoteSnapshot if available, or an empty Optional if not found
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

    /**
     * Fetches the latest quote for every instrument in one call, authenticating with the given
     * token - for work with no request behind it, such as watching open orders.
     * @param bearerToken a market-quotes service token
     * @return the latest quotes, empty if the market data service has none
     * @throws QuoteUnavailableException if the market data service can't be reached
     */
    public List<QuoteSnapshot> fetchAllAs(String bearerToken) {
        try {
            List<QuoteSnapshot> quotes = restClient.get()
                    .uri("/api/marketdata/quotes")
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<QuoteSnapshot>>() {});
            return quotes != null ? quotes : List.of();
        } catch (RestClientException ex) {
            throw new QuoteUnavailableException("Failed to fetch current quotes", ex);
        }
    }

    /**
     * Forwards the caller's JWT token from the current HTTP request to the given headers for the market data service.
     * @param headers the HTTP headers to which the caller's JWT token should be forwarded
     */
    private void forwardCallerToken(HttpHeaders headers) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
        }
    }
}

