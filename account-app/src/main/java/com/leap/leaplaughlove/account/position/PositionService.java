package com.leap.leaplaughlove.account.position;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountAuthorizationService;
import com.leap.leaplaughlove.account.account.AccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Service class for managing trading positions within client accounts.
 * Provides methods to retrieve and transform position data for client accounts.
 */
@Service
public class PositionService {

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final AccountAuthorizationService accountAuthorizationService;

    /**
     * Constructs a new PositionService with the injected repositories and authorization service.
     * @param accountRepository the repository for accessing account data
     * @param positionRepository the repository for accessing position data
     * @param accountAuthorizationService the service for authorizing account access
     */
    @Autowired
    public PositionService(AccountRepository accountRepository,
                           PositionRepository positionRepository,
                           AccountAuthorizationService accountAuthorizationService) {
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.accountAuthorizationService = accountAuthorizationService != null
                ? accountAuthorizationService
                : new AccountAuthorizationService(accountRepository);
    }

    /**
     * Constructs a new PositionService with the injected repositories and a default constructed authorization service.
     * @param accountRepository the repository for accessing account data
     * @param positionRepository the repository for accessing position data
     */
    public PositionService(AccountRepository accountRepository, PositionRepository positionRepository) {
        this(accountRepository, positionRepository, new AccountAuthorizationService(accountRepository));
    }

    /**
     * Retrieves current holdings for a specific authenticated client account.
     * @param accountId the unique identifier of the authenticated client account
     * @return a response object containing the positions for the account
     */
    public PositionsResponse getPositionsForAuthenticatedClientAccount(UUID accountId) {
        Account account = accountAuthorizationService.getAuthorizedAccount(accountId);
        return toPositionsResponse(account);
    }

    /**
     * Converts an account Entity into a PositionsResponse DTO
     * @param account the account entity to be converted
     * @return a PositionsResponse DTO representing the account's positions
     */
    public PositionsResponse toPositionsResponse(Account account) {
        List<PositionItem> items = positionRepository.findPositionsByAccountId(account.getAccountId()).stream()
                .map(row -> new PositionItem(
                        UUID.fromString(row.getInstrumentId()),
                        row.getSymbol(),
                        row.getInstrumentName(),
                        row.getAssetClass(),
                        row.getQuantity(),
                        row.getAvgCost()))
                .toList();

        return new PositionsResponse(
                account.getAccountId(),
                account.getAccountNumber(),
                account.getBaseCurrency(),
                items);
    }
}

