package com.leap.leaplaughlove.account.account;

import com.leap.leaplaughlove.common.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for managing accounts.
 * Provides endpoints to retrieve account summaries and details for the
 * authenticated client.
 */
@RestController
@RequestMapping("/api/account/accounts")
public class AccountController {

    private final AccountRepository accountRepository;
    private final AccountAuthorizationService accountAuthorizationService;
    private final AccountService accountService;

    /**
     * Constructs an AccountController with the injected account repository,
     * account authorization service and account service.
     *
     * @param accountRepository           the repository used to access account data
     * @param accountAuthorizationService the service used to perform authorization
     *                                    checks on accounts
     * @param accountService              the service used to open new accounts
     */
    public AccountController(AccountRepository accountRepository,
            AccountAuthorizationService accountAuthorizationService,
            AccountService accountService) {
        this.accountRepository = accountRepository;
        this.accountAuthorizationService = accountAuthorizationService;
        this.accountService = accountService;
    }

    /**
     * Opens a new account for the authenticated client.
     *
     * @param request the creation request; the body may be omitted to default to USD
     * @return the summary of the newly created account
     */
    @PostMapping
    public ResponseEntity<AccountSummary> createAccount(@RequestBody(required = false) CreateAccountRequest request) {
        Account account = accountService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toSummary(account));
    }

    /**
     * Retrieves a list of account summaries for the authenticated client.
     * Only accounts with ACTIVE status are included.
     * 
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
     * 
     * @param accountId the unique identifier of the account to retrieve
     * @return the account summary for the specified account if it exists and
     *         belongs to the authenticated client
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountSummary> getAccount(@PathVariable UUID accountId) {
        Account account = accountAuthorizationService.getAuthorizedAccount(accountId);
        return ResponseEntity.ok(toSummary(account));
    }

    /**
     * Saves the trading settings of one of the authenticated client's accounts, such as the
     * price tolerance order-app applies to orders that don't carry their own.
     *
     * @param accountId the unique identifier of the account to update
     * @param request   the settings to save
     * @return the updated account summary
     */
    @PutMapping("/{accountId}/trade-settings")
    public ResponseEntity<AccountSummary> updateTradeSettings(@PathVariable UUID accountId,
            @Valid @RequestBody TradeSettingsRequest request) {
        Account account = accountService.updateTradeSettings(accountId, request);
        return ResponseEntity.ok(toSummary(account));
    }

    /**
     * Helper method to convert an Account entity to an AccountSummary DTO.
     * 
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
                account.getMaxSlippagePercent(),
                account.getCreatedAt(),
                account.getInactiveSince());
    }
}
