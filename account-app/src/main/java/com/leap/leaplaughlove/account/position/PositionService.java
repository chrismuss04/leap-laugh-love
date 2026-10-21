package com.leap.leaplaughlove.account.position;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountAuthorizationService;
import com.leap.leaplaughlove.account.account.AccountRepository;
import com.leap.leaplaughlove.account.common.TradingDay;
import com.leap.leaplaughlove.account.instrument.Instrument;
import com.leap.leaplaughlove.account.instrument.InstrumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service class for managing trading positions within client accounts.
 * Provides methods to retrieve and transform position data for client accounts.
 */
@Service
public class PositionService {

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final PositionMovementRepository positionMovementRepository;
    private final InstrumentRepository instrumentRepository;
    private final AccountAuthorizationService accountAuthorizationService;
    private final Clock clock;

    /**
     * Constructs a new PositionService on the system clock.
     * @param accountRepository the repository for accessing account data
     * @param positionRepository the repository for accessing position data
     * @param positionMovementRepository the repository for today's buys and sells
     * @param instrumentRepository the repository for naming positions closed today
     * @param accountAuthorizationService the service for authorizing account access
     */
    @Autowired
    public PositionService(AccountRepository accountRepository,
                           PositionRepository positionRepository,
                           PositionMovementRepository positionMovementRepository,
                           InstrumentRepository instrumentRepository,
                           AccountAuthorizationService accountAuthorizationService) {
        this(accountRepository, positionRepository, positionMovementRepository, instrumentRepository,
                accountAuthorizationService, Clock.systemUTC());
    }

    /**
     * Constructs a new PositionService on a given clock.
     * @param accountRepository the repository for accessing account data
     * @param positionRepository the repository for accessing position data
     * @param positionMovementRepository the repository for today's buys and sells
     * @param instrumentRepository the repository for naming positions closed today
     * @param accountAuthorizationService the service for authorizing account access, or null for a default one
     * @param clock the clock that says when today began
     */
    PositionService(AccountRepository accountRepository,
                    PositionRepository positionRepository,
                    PositionMovementRepository positionMovementRepository,
                    InstrumentRepository instrumentRepository,
                    AccountAuthorizationService accountAuthorizationService,
                    Clock clock) {
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.positionMovementRepository = positionMovementRepository;
        this.instrumentRepository = instrumentRepository;
        this.accountAuthorizationService = accountAuthorizationService != null
                ? accountAuthorizationService
                : new AccountAuthorizationService(accountRepository);
        this.clock = clock;
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
     * Converts an account Entity into a PositionsResponse DTO, with what each position did today.
     * @param account the account entity to be converted
     * @return a PositionsResponse DTO representing the account's positions
     */
    public PositionsResponse toPositionsResponse(Account account) {
        Map<UUID, Today> today = today(account.getAccountId());

        Set<UUID> held = new HashSet<>();
        List<PositionItem> items = positionRepository.findPositionsByAccountId(account.getAccountId()).stream()
                .map(row -> {
                    UUID instrumentId = UUID.fromString(row.getInstrumentId());
                    held.add(instrumentId);
                    Today moved = today.getOrDefault(instrumentId, Today.NONE);
                    return new PositionItem(
                            instrumentId,
                            row.getSymbol(),
                            row.getInstrumentName(),
                            row.getAssetClass(),
                            row.getQuantity(),
                            row.getAvgCost(),
                            row.getQuantity() - moved.quantityDelta(),
                            moved.cashFlow());
                })
                .toList();

        return new PositionsResponse(
                account.getAccountId(),
                account.getAccountNumber(),
                account.getBaseCurrency(),
                items,
                closedToday(today, held));
    }

    /** Net quantity and cash moved per instrument since the trading day began. */
    private Map<UUID, Today> today(UUID accountId) {
        OffsetDateTime start = TradingDay.startOf(OffsetDateTime.now(clock));
        Map<UUID, Today> today = new HashMap<>();
        for (PositionMovement movement : positionMovementRepository.findByAccountIdInAndCreatedAtAfter(
                List.of(accountId), start)) {
            // A movement's cost is what the shares cost on a buy and what they fetched on a sell, as
            // negative; the cash the position returned is the opposite.
            today.merge(movement.getInstrumentId(),
                    new Today(movement.getQuantityDelta(), movement.getCostDelta().negate()), Today::plus);
        }
        return today;
    }

    /** Instruments traded today that the account no longer holds, alphabetically like positions. */
    private List<PositionItem> closedToday(Map<UUID, Today> today, Set<UUID> held) {
        Set<UUID> closed = new HashSet<>(today.keySet());
        closed.removeAll(held);
        if (closed.isEmpty()) {
            return List.of();
        }
        List<PositionItem> items = new ArrayList<>();
        for (Instrument instrument : instrumentRepository.findAllById(closed)) {
            Today moved = today.get(instrument.getInstrumentId());
            items.add(new PositionItem(
                    instrument.getInstrumentId(),
                    instrument.getSymbol(),
                    instrument.getInstrumentName(),
                    instrument.getAssetClass(),
                    0L,
                    BigDecimal.ZERO,
                    -moved.quantityDelta(),
                    moved.cashFlow()));
        }
        items.sort(Comparator.comparing(PositionItem::symbol));
        return items;
    }

    /** What one instrument did today: shares bought less sold, and cash in less cash out. */
    private record Today(long quantityDelta, BigDecimal cashFlow) {
        static final Today NONE = new Today(0, BigDecimal.ZERO);

        Today plus(Today other) {
            return new Today(quantityDelta + other.quantityDelta, cashFlow.add(other.cashFlow));
        }
    }
}
