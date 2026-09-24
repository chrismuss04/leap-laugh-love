package com.leap.leaplaughlove.order.client;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

/**
 * Client for interacting with account-related endpoints.
 * Provides methods to retrieve account validation data, settle orders, and fetch account summaries from account-app
 */
@Component
public class AccountClient {

    private final RestClient restClient;

    /**
     * Constructs an AccountClient with the given RestClient for account service communication.
     * @param accountRestClient the RestClient used for communicating with the account service
     */
    public AccountClient(RestClient accountRestClient) {
        this.restClient = accountRestClient;
    }

    /**
     * Retrieves the account validation data for the specified account and instrument.
     * @param accountId the ID of the account
     * @param instrumentId the ID of the instrument
     * @return the account validation data for the specified account and instrument
     */
    public AccountValidationDto getValidationData(UUID accountId, UUID instrumentId) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/account/internal/accounts/{accountId}/validation-data")
                        .queryParam("instrumentId", instrumentId)
                        .build(accountId))
                .headers(this::forwardCallerToken)
                .retrieve()
                .body(AccountValidationDto.class);
    }

    /**
     * Settles an order for the specified account.
     * @param accountId the ID of the account
     * @param request the settlement request containing order details
     * @return the settlement response for the order
     */
    public SettlementResponse settleOrder(UUID accountId, SettlementRequest request) {
        return restClient.post()
                .uri("/api/account/internal/accounts/{accountId}/settlement", accountId)
                .headers(this::forwardCallerToken)
                .body(request)
                .retrieve()
                .body(SettlementResponse.class);
    }

    /**
     * Settles an order for the specified account, authenticating with the given token rather
     * than the current request's - for work with no request behind it, such as booking seeded fills.
     * @param accountId the ID of the account
     * @param request the settlement request containing order details
     * @param bearerToken the JWT to send, issued to the account's owner
     * @return the settlement response for the order
     */
    public SettlementResponse settleOrderAs(UUID accountId, SettlementRequest request, String bearerToken) {
        return restClient.post()
                .uri("/api/account/internal/accounts/{accountId}/settlement", accountId)
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .body(request)
                .retrieve()
                .body(SettlementResponse.class);
    }

    /**
     * Retrieves the list of account IDs for the currently authenticated client.
     * @return a list of account IDs for the client
     */
    public List<UUID> getAccountIdsForClient() {
        List<AccountSummaryDto> accounts = restClient.get()
                .uri("/api/account/accounts")
                .headers(this::forwardCallerToken)
                .retrieve()
                .body(new ParameterizedTypeReference<List<AccountSummaryDto>>() {});
        if (accounts == null) {
            return List.of();
        }
        return accounts.stream().map(AccountSummaryDto::accountId).toList();
    }

    /**
     * Forwards the caller's authorization token to the headers of the outgoing request for account-app to use.
     * @param headers the HttpHeaders to which the authorization token should be added
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

