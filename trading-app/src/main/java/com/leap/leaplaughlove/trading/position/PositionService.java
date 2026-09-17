package com.leap.leaplaughlove.trading.position;

import com.leap.leaplaughlove.common.security.SecurityUtils;
import com.leap.leaplaughlove.trading.account.Account;
import com.leap.leaplaughlove.trading.account.AccountAuthorizationService;
import com.leap.leaplaughlove.trading.account.AccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class PositionService {

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final AccountAuthorizationService accountAuthorizationService;

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

    public PositionService(AccountRepository accountRepository, PositionRepository positionRepository) {
        this(accountRepository, positionRepository, new AccountAuthorizationService(accountRepository));
    }

    public PositionsResponse getPositionsForAuthenticatedClientAccount(UUID accountId) {
        Account account = accountAuthorizationService.getAuthorizedAccount(accountId);
        return toPositionsResponse(account);
    }

    /**
     * Retrieves current holdings across every active account belonging to the authenticated
     * client, with no account ID required from the caller.
     * @return the holdings response, one entry per active account
     */
    public ClientPositionsResponse getHoldingsForAuthenticatedClient() {
        UUID clientId = SecurityUtils.getAuthenticatedClientId();
        List<Account> accounts = accountRepository.findByClientIdAndStatus(
                clientId, AccountAuthorizationService.ACTIVE_STATUS);

        List<PositionsResponse> holdings = accounts.stream()
                .map(this::toPositionsResponse)
                .toList();

        return new ClientPositionsResponse(holdings);
    }

    private PositionsResponse toPositionsResponse(Account account) {
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
