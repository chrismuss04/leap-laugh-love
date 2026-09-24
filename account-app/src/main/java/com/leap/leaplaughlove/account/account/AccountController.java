package com.leap.leaplaughlove.account.account;

import com.leap.leaplaughlove.common.security.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for managing accounts.
 * Provides endpoints to retrieve account summaries and details for the authenticated client.
 */
@RestController
@RequestMapping("/api/account/accounts")
public class AccountController {

    private final AccountRepository accountRepository;
    private final AccountAuthorizationService accountAuthorizationService;

    /**
     * Constructs an AccountController with the injected account repository and account authorization service.
     * @param accountRepository the repository used to access account data
     * @param accountAuthorizationService the service used to perform authorization checks on accounts
     */
    public AccountController(AccountRepository accountRepository,
                             AccountAuthorizationService accountAuthorizationService) {
        this.accountRepository = accountRepository;
        this.accountAuthorizationService = accountAuthorizationService;
    }

    /**
     * Retrieves a list of account summaries for the authenticated client.
     * Only accounts with ACTIVE status are included.
     * @return a list of account summaries for the authenticated client
     */
    @GetMapping
    public ResponseEntity<List<AccountSummary>> getAccountsForClient() {
        UUID clientId = SecurityUtils.getAuthenticatedClientId();
        List<Account> accounts = accountRepository.findByClientIdAndStatus(
                clientId, AccountAuthorizationService.ACTIVE_STATUS);
        List<AccountSummary> summaries = accounts.stream()
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(summaries);
    }

    /**
     * Retrieves the summary of a specific account for the authenticated client.
     * @param accountId the unique identifier of the account to retrieve
     * @return the account summary for the specified account if it exists and belongs to the authenticated client
     * @throws ResponseStatusException if the account is not found or is not active
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountSummary> getAccount(@PathVariable UUID accountId) {
        Account account = accountAuthorizationService.getAuthorizedAccount(accountId);
        return ResponseEntity.ok(toSummary(account));
    }

    /**
     * Helper method to convert an Account entity to an AccountSummary DTO.
     * @param account the account entity to be converted
     * @return the corresponding account summary DTO
     */
    private AccountSummary toSummary(Account account) {
        return new AccountSummary(
                account.getAccountId(),
                account.getAccountNumber(),
                account.getStatus(),
                account.getBaseCurrency(),
                account.isTradingEnabled(),
                account.getCreatedAt());
    }
}

