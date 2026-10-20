package com.leap.leaplaughlove.common.marketdata;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Fetches candle history and latest prices from the market data service, for valuing holdings
 * over time. The caller's own bearer token is forwarded because the market data endpoints are
 * JWT-secured. Each service subclasses this to raise its own "quote unavailable" exception.
 */
public abstract class MarketDataPriceClient {

    /** The largest page the market data history endpoint will serve. */
    public static final int PAGE_SIZE = 1000;

    /** Guards against paging forever if the market data service misreports its page count. */
    private static final int MAX_PAGES = 20;

    private final RestClient restClient;

    /**
     * Creates a new MarketDataPriceClient.
     * @param marketDataRestClient the RestClient configured for the market data service
     */
    protected MarketDataPriceClient(RestClient marketDataRestClient) {
        this.restClient = marketDataRestClient;
    }

    /**
     * Builds the exception thrown when the market data service could not be reached or
     * returned an error.
     * @param message the detail message
     * @param cause the underlying client failure
     * @return the exception to throw
     */
    protected abstract RuntimeException quoteUnavailable(String message, Throwable cause);

    /**
     * Fetches every candle close for a symbol within a time range, oldest first.
     * @param symbol the instrument symbol
     * @param from the start of the range
     * @param to the end of the range
     * @param intervalSeconds the candle width, in seconds (60, 300, 3600 or 86400)
     * @return the candle closes in the range, oldest first; empty if the symbol has no history
     */
    public List<CandleClose> fetchCloses(String symbol, OffsetDateTime from, OffsetDateTime to,
                                         int intervalSeconds) {
        return fetchCloses(symbol, from, to, intervalSeconds, this::forwardCallerToken);
    }

    /**
     * Fetches every candle close for a symbol within a time range, oldest first, authenticating
     * with the given token instead of the caller's. For work that runs outside any request.
     * @param symbol the instrument symbol
     * @param from the start of the range
     * @param to the end of the range
     * @param intervalSeconds the candle width, in seconds (60, 300, 3600 or 86400)
     * @param bearerToken the JWT to send to the market data service
     * @return the candle closes in the range, oldest first; empty if the symbol has no history
     */
    public List<CandleClose> fetchCloses(String symbol, OffsetDateTime from, OffsetDateTime to,
                                         int intervalSeconds, String bearerToken) {
        return fetchCloses(symbol, from, to, intervalSeconds, headers -> headers.setBearerAuth(bearerToken));
    }

    /**
     * Fetches every candle close for a symbol within a time range, oldest first, with custom authentication.
     * @param symbol the instrument symbol
     * @param from the start of the range
     * @param to the end of the range
     * @param intervalSeconds the candle width, in seconds
     * @param authentication consumer to populate authentication headers
     * @return the candle closes in the range, oldest first
     */
    private List<CandleClose> fetchCloses(String symbol, OffsetDateTime from, OffsetDateTime to,
                                          int intervalSeconds, Consumer<HttpHeaders> authentication) {
        List<CandleClose> closes = new ArrayList<>();
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                int pageNumber = page;
                CandlePage body = restClient.get()
                        .uri(uri -> uri.path("/api/marketdata/prices/{symbol}/history")
                                .queryParam("from", isoUtc(from))
                                .queryParam("to", isoUtc(to))
                                .queryParam("interval", intervalSeconds)
                                .queryParam("page", pageNumber)
                                .queryParam("size", PAGE_SIZE)
                                .build(marketDataSymbol(symbol)))
                        .headers(authentication)
                        .retrieve()
                        .body(CandlePage.class);
                if (body == null || body.content() == null) {
                    break;
                }
                closes.addAll(body.content());
                if (body.last() || body.content().isEmpty()) {
                    break;
                }
            }
        } catch (HttpClientErrorException.NotFound ex) {
            return List.of();
        } catch (RestClientException ex) {
            throw quoteUnavailable("Failed to fetch price history for symbol " + symbol, ex);
        }
        // The history endpoint is newest-first; valuation walks forward in time.
        closes.sort((a, b) -> a.bucketStart().compareTo(b.bucketStart()));
        return closes;
    }

    /**
     * Fetches the latest simulated price for a symbol.
     * @param symbol the instrument symbol
     * @return the latest price, or empty if the market data service does not know the symbol
     */
    public Optional<BigDecimal> fetchLatestPrice(String symbol) {
        try {
            LatestPrice latest = restClient.get()
                    .uri("/api/marketdata/prices/{symbol}", marketDataSymbol(symbol))
                    .headers(this::forwardCallerToken)
                    .retrieve()
                    .body(LatestPrice.class);
            return Optional.ofNullable(latest).map(LatestPrice::price);
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (RestClientException ex) {
            throw quoteUnavailable("Failed to fetch latest price for symbol " + symbol, ex);
        }
    }

    /**
     * The market data service's name for a trading symbol: the same, less any "/", which can't
     * appear in a URL path segment (BTC/USD trades as BTCUSD there).
     * @param tradingSymbol the symbol trading.instruments holds
     * @return the market data symbol
     */
    private static String marketDataSymbol(String tradingSymbol) {
        return tradingSymbol.replace("/", "");
    }

    /**
     * Converts an offset date-time to a UTC ISO-8601 string truncated to seconds.
     * @param time the offset date-time to format
     * @return the ISO-8601 string representation in UTC
     */
    private static String isoUtc(OffsetDateTime time) {
        return time.toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * Forwards the incoming caller's bearer authorization header to outgoing requests.
     * @param headers the HTTP headers of the outgoing request
     */
    private void forwardCallerToken(HttpHeaders headers) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
        }
    }

    /**
     * One candle's close, the only field valuation needs.
     * @param bucketStart the start of the candle's time bucket
     * @param close the closing price of the bucket
     */
    public record CandleClose(OffsetDateTime bucketStart, BigDecimal close) {
    }

    /** The subset of a Spring Data page the client reads. */
    record CandlePage(List<CandleClose> content, boolean last) {
    }

    /** The subset of the market data latest-price response the client reads. */
    record LatestPrice(String symbol, BigDecimal price) {
    }
}
