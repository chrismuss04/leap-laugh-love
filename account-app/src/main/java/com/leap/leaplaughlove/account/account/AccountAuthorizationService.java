package com.leap.leaplaughlove.account.account;

import com.leap.leaplaughlove.common.security.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Service providing reusable authorization checks on accounts for the authenticated client.
 */
@Service
public class AccountAuthorizationService {

    public static final String ACTIVE_STATUS = "ACTIVE";

    private final AccountRepository accountRepository;

    /**
     * Constructs an AccountAuthorizationService with the specified account repository.
     * @param accountRepository the repository used to access account data
     */
    public AccountAuthorizationService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Verifies that the specified account exists, belongs to the authenticated client, and is in ACTIVE status.
     * @param accountId the unique identifier of the account to be authorized
     * @return the authorized account if it exists, belongs to the authenticated client, and is active
     * @throws ResponseStatusException if the account is not found or is not active
     */
    public Account getAuthorizedAccount(UUID accountId) {
        UUID clientId = SecurityUtils.getAuthenticatedClientId();
        Account account = accountRepository.findByAccountIdAndClientId(accountId, clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        if (!ACTIVE_STATUS.equals(account.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account is not active");
        }

        return account;
    }

    /**
     * Verifies that the specified account exists, belongs to the authenticated client, is in ACTIVE status, and has trading enabled.
     * @param accountId the unique identifier of the account to be authorized for trading
     * @return the authorized trading account if it exists, belongs to the authenticated client, is active, and has trading enabled
     * @throws ResponseStatusException if the account is not found, is not active, or trading is disabled for the account
     */
    public Account getAuthorizedTradingAccount(UUID accountId) {
        Account account = getAuthorizedAccount(accountId);

        if (!account.isTradingEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trading is disabled for this account");
        }

        return account;
    }
}

