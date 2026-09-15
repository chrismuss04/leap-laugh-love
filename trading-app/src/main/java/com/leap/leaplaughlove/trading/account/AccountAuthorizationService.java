package com.leap.leaplaughlove.trading.account;

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

    public AccountAuthorizationService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Verifies that the specified account exists, belongs to the authenticated client,
     * and is in ACTIVE status.
     *
     * @param accountId the account ID to verify
     * @return the authorized Account entity
     * @throws ResponseStatusException with 404 NOT_FOUND if the account is not owned by the client,
     *                                 or 400 BAD_REQUEST if the account is not ACTIVE
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
     * Verifies that the specified account exists, belongs to the authenticated client,
     * is in ACTIVE status, and has trading enabled (account is not locked for trading).
     *
     * @param accountId the account ID to verify
     * @return the authorized Account entity
     * @throws ResponseStatusException with 404 NOT_FOUND if not owned,
     *                                 or 400 BAD_REQUEST if inactive or trading is disabled
     */
    public Account getAuthorizedTradingAccount(UUID accountId) {
        Account account = getAuthorizedAccount(accountId);

        if (!account.isTradingEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trading is disabled for this account");
        }

        return account;
    }
}
