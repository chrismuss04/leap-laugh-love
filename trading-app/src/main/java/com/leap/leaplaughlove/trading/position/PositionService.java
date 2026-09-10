package com.leap.leaplaughlove.trading.position;

import com.leap.leaplaughlove.trading.account.Account;
import com.leap.leaplaughlove.trading.account.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class PositionService {

    private static final String ACTIVE_STATUS = "ACTIVE";

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;

    public PositionService(AccountRepository accountRepository, PositionRepository positionRepository) {
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
    }

    public PositionsResponse getPositionsForAuthenticatedClientAccount(UUID accountId) {
        UUID clientId = getAuthenticatedClientId();
        Account account = accountRepository.findByAccountIdAndClientId(accountId, clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        if (!ACTIVE_STATUS.equals(account.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account is not active");
        }

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

    private UUID getAuthenticatedClientId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "A valid authenticated principal is required");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID clientId) {
            return clientId;
        }
        if (principal instanceof String principalString && !principalString.isBlank()
                && !"anonymousUser".equals(principalString)) {
            try {
                return UUID.fromString(principalString);
            } catch (IllegalArgumentException ignored) {
                // commment fall through to unauthorized
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Authenticated principal is invalid for this operation");
    }
}
