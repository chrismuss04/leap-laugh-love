package com.leap.leaplaughlove.iam.account;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Client for the account-app endpoints iam-app uses to set up a newly registered client's first
 * account. Every call authenticates with a token issued to that client.
 */
@Component
public class AccountClient {

    private final RestClient restClient;

    /**
     * Constructs an AccountClient.
     * @param accountRestClient the RestClient used to reach account-app
     */
    public AccountClient(RestClient accountRestClient) {
        this.restClient = accountRestClient;
    }

    /**
     * Opens an account for the client the token was issued to.
     * @param bearerToken a JWT issued to the account's owner
     * @param baseCurrency the currency of the new account
     * @return the id of the account account-app created
     * @throws IllegalStateException if account-app answers without an account id
     */
    public UUID createAccount(String bearerToken, String baseCurrency) {
        CreatedAccount created = restClient.post()
                .uri("/api/account/accounts")
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .body(new CreateAccountBody(baseCurrency))
                .retrieve()
                .body(CreatedAccount.class);
        if (created == null || created.accountId() == null) {
            throw new IllegalStateException("account-app did not return an account id");
        }
        return created.accountId();
    }

    /**
     * Deposits cash into an account owned by the client the token was issued to.
     * @param bearerToken a JWT issued to the account's owner
     * @param accountId the account to deposit into
     * @param amount the amount to deposit
     * @param description the note recorded on the ledger entry
     */
    public void deposit(String bearerToken, UUID accountId, BigDecimal amount, String description) {
        restClient.post()
                .uri("/api/account/balance/accounts/{accountId}/deposit", accountId)
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .body(new DepositBody(amount, description))
                .retrieve()
                .toBodilessEntity();
    }

    record CreateAccountBody(String baseCurrency) {}

    record DepositBody(BigDecimal amount, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreatedAccount(UUID accountId) {}
}
