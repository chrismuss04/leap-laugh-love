package com.leap.leaplaughlove.account.position;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountAuthorizationService;
import com.leap.leaplaughlove.account.account.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PositionService Unit Tests")
class PositionServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private AccountAuthorizationService accountAuthorizationService;

    private PositionService positionService;

    private UUID accountId;
    private UUID clientId;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        positionService = new PositionService(accountRepository, positionRepository, accountAuthorizationService);
        accountId = UUID.randomUUID();
        clientId = UUID.randomUUID();
        testAccount = new Account(accountId, clientId, "ACC-999", "ACTIVE", "USD", true, OffsetDateTime.now());
    }

    private PositionRepository.PositionRow createMockRow(String instrumentId, String symbol, String name,
                                                         String assetClass, long quantity, BigDecimal avgCost) {
        return new PositionRepository.PositionRow() {
            @Override
            public String getInstrumentId() {
                return instrumentId;
            }

            @Override
            public String getSymbol() {
                return symbol;
            }

            @Override
            public String getInstrumentName() {
                return name;
            }

            @Override
            public String getAssetClass() {
                return assetClass;
            }

            @Override
            public long getQuantity() {
                return quantity;
            }

            @Override
            public BigDecimal getAvgCost() {
                return avgCost;
            }
        };
    }

    @Test
    @DisplayName("getPositionsForAuthenticatedClientAccount returns mapped PositionsResponse")
    void testGetPositionsForAuthenticatedClientAccount() {
        when(accountAuthorizationService.getAuthorizedAccount(accountId)).thenReturn(testAccount);

        UUID inst1 = UUID.randomUUID();
        UUID inst2 = UUID.randomUUID();
        var row1 = createMockRow(inst1.toString(), "AAPL", "Apple Inc.", "EQUITY", 100L, new BigDecimal("150.50"));
        var row2 = createMockRow(inst2.toString(), "MSFT", "Microsoft Corp.", "EQUITY", 50L, new BigDecimal("310.20"));

        when(positionRepository.findPositionsByAccountId(accountId)).thenReturn(List.of(row1, row2));

        PositionsResponse response = positionService.getPositionsForAuthenticatedClientAccount(accountId);

        assertNotNull(response);
        assertEquals(accountId, response.accountId());
        assertEquals("ACC-999", response.accountNumber());
        assertEquals("USD", response.baseCurrency());
        assertEquals(2, response.positions().size());

        PositionItem item1 = response.positions().get(0);
        assertEquals(inst1, item1.instrumentId());
        assertEquals("AAPL", item1.symbol());
        assertEquals("Apple Inc.", item1.instrumentName());
        assertEquals("EQUITY", item1.assetClass());
        assertEquals(100L, item1.quantity());
        assertEquals(new BigDecimal("150.50"), item1.averageCost());

        verify(accountAuthorizationService).getAuthorizedAccount(accountId);
        verify(positionRepository).findPositionsByAccountId(accountId);
    }

    @Test
    @DisplayName("the same instrument held in two accounts of one client has independent quantity and cost")
    void testHoldingsAreIndependentPerAccount() {
        UUID secondAccountId = UUID.randomUUID();
        Account secondAccount = new Account(secondAccountId, clientId, "ACC-998", "ACTIVE", "USD", true,
                OffsetDateTime.now());
        String aapl = UUID.randomUUID().toString();
        String msft = UUID.randomUUID().toString();

        when(accountAuthorizationService.getAuthorizedAccount(accountId)).thenReturn(testAccount);
        when(accountAuthorizationService.getAuthorizedAccount(secondAccountId)).thenReturn(secondAccount);
        when(positionRepository.findPositionsByAccountId(accountId)).thenReturn(List.of(
                createMockRow(aapl, "AAPL", "Apple Inc.", "EQUITY", 100L, new BigDecimal("150.00"))));
        when(positionRepository.findPositionsByAccountId(secondAccountId)).thenReturn(List.of(
                createMockRow(aapl, "AAPL", "Apple Inc.", "EQUITY", 7L, new BigDecimal("210.00")),
                createMockRow(msft, "MSFT", "Microsoft Corp.", "EQUITY", 3L, new BigDecimal("300.00"))));

        PositionsResponse first = positionService.getPositionsForAuthenticatedClientAccount(accountId);
        PositionsResponse second = positionService.getPositionsForAuthenticatedClientAccount(secondAccountId);

        assertEquals(accountId, first.accountId());
        assertEquals(1, first.positions().size());
        assertEquals(100L, first.positions().get(0).quantity());
        assertEquals(new BigDecimal("150.00"), first.positions().get(0).averageCost());

        assertEquals(secondAccountId, second.accountId());
        assertEquals(2, second.positions().size());
        assertEquals(7L, second.positions().get(0).quantity());
        assertEquals(new BigDecimal("210.00"), second.positions().get(0).averageCost());
        assertEquals("MSFT", second.positions().get(1).symbol());
    }

    @Test
    @DisplayName("toPositionsResponse returns empty positions list when account has no positions")
    void testToPositionsResponseEmpty() {
        when(positionRepository.findPositionsByAccountId(accountId)).thenReturn(List.of());

        PositionsResponse response = positionService.toPositionsResponse(testAccount);

        assertNotNull(response);
        assertEquals(accountId, response.accountId());
        assertTrue(response.positions().isEmpty());
    }

    @Test
    @DisplayName("getPositionsForAuthenticatedClientAccount throws when account authorization fails")
    void testGetPositionsUnauthorizedThrows() {
        when(accountAuthorizationService.getAuthorizedAccount(accountId))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"));

        assertThrows(ResponseStatusException.class,
                () -> positionService.getPositionsForAuthenticatedClientAccount(accountId));
    }

    @Test
    @DisplayName("constructors initialize properly with default or null authorization service")
    void testConstructors() {
        PositionService service2Arg = new PositionService(accountRepository, positionRepository);
        assertNotNull(service2Arg);

        PositionService service3ArgNullAuth = new PositionService(accountRepository, positionRepository, null);
        assertNotNull(service3ArgNullAuth);
    }
}

