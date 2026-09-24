package com.leap.leaplaughlove.account.settlement;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountAuthorizationService;
import com.leap.leaplaughlove.account.balance.BalanceService;
import com.leap.leaplaughlove.account.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.account.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.account.position.Position;
import com.leap.leaplaughlove.account.position.PositionId;
import com.leap.leaplaughlove.account.position.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountSettlementService Unit Tests")
class AccountSettlementServiceTest {

    private static final UUID CLIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACCOUNT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID INSTRUMENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001");

    @Mock private AccountAuthorizationService accountAuthorizationService;
    @Mock private BalanceService balanceService;
    @Mock private CashLedgerRepository cashLedgerRepository;
    @Mock private PositionRepository positionRepository;

    private AccountSettlementService service;
    private Account account;

    @BeforeEach
    void setUp() {
        service = new AccountSettlementService(
                accountAuthorizationService, balanceService, cashLedgerRepository, positionRepository);
        account = new Account(ACCOUNT_ID, CLIENT_ID, "ACC-TEST", "ACTIVE", "USD", true, OffsetDateTime.now());
    }

    @Test
    @DisplayName("Pre-trade validation returns eligibility and balance")
    void testGetValidationData() {
        when(accountAuthorizationService.getAuthorizedAccount(ACCOUNT_ID)).thenReturn(account);
        when(balanceService.getCurrentBalance(account)).thenReturn(new BigDecimal("5000.00"));
        Position position = new Position(ACCOUNT_ID, INSTRUMENT_ID, 25L, new BigDecimal("100.00"), OffsetDateTime.now());
        when(positionRepository.findById(new PositionId(ACCOUNT_ID, INSTRUMENT_ID))).thenReturn(Optional.of(position));

        AccountValidationDto dto = service.getValidationData(ACCOUNT_ID, INSTRUMENT_ID);

        assertTrue(dto.accountActive());
        assertTrue(dto.tradingEnabled());
        assertEquals(new BigDecimal("5000.00"), dto.cashBalance());
        assertEquals(25L, dto.holdingQuantity());
        assertEquals("USD", dto.baseCurrency());
        assertEquals("ACC-TEST", dto.accountNumber());
    }

    @Test
    @DisplayName("Settle BUY order creates new position when none existed")
    void testSettleBuyOrder_newPosition() {
        when(accountAuthorizationService.getAuthorizedTradingAccountForUpdate(ACCOUNT_ID)).thenReturn(account);
        when(positionRepository.findByIdForUpdate(ACCOUNT_ID, INSTRUMENT_ID)).thenReturn(Optional.empty());
        CashLedgerEntry savedLedger = new CashLedgerEntry(
                ACCOUNT_ID, UUID.randomUUID(), UUID.randomUUID(), "BUY_SETTLEMENT",
                new BigDecimal("-1500.00"), "USD", OffsetDateTime.now(), "Buy settlement");
        when(cashLedgerRepository.save(any(CashLedgerEntry.class))).thenReturn(savedLedger);
        when(balanceService.getCurrentBalance(account)).thenReturn(new BigDecimal("3500.00"));

        SettlementRequest request = new SettlementRequest(
                UUID.randomUUID(), UUID.randomUUID(), INSTRUMENT_ID, "AAPL", "BUY",
                10, new BigDecimal("150.00"), OffsetDateTime.now());

        SettlementResponse response = service.settleOrder(ACCOUNT_ID, request);

        assertEquals(10L, response.positionQuantity());
        assertEquals(new BigDecimal("150.00"), response.positionAvgCost());
        assertEquals(new BigDecimal("3500.00"), response.balanceAfter());

        ArgumentCaptor<Position> posCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepository).save(posCaptor.capture());
        assertEquals(10L, posCaptor.getValue().getQuantity());
        assertEquals(new BigDecimal("150.00"), posCaptor.getValue().getAvgCost());
    }

    @Test
    @DisplayName("Settle BUY order updates average cost for existing position")
    void testSettleBuyOrder_existingPosition() {
        when(accountAuthorizationService.getAuthorizedTradingAccountForUpdate(ACCOUNT_ID)).thenReturn(account);
        Position existing = new Position(ACCOUNT_ID, INSTRUMENT_ID, 10L, new BigDecimal("100.00"), OffsetDateTime.now());
        when(positionRepository.findByIdForUpdate(ACCOUNT_ID, INSTRUMENT_ID)).thenReturn(Optional.of(existing));
        CashLedgerEntry savedLedger = new CashLedgerEntry(
                ACCOUNT_ID, UUID.randomUUID(), UUID.randomUUID(), "BUY_SETTLEMENT",
                new BigDecimal("-2000.00"), "USD", OffsetDateTime.now(), "Buy settlement");
        when(cashLedgerRepository.save(any(CashLedgerEntry.class))).thenReturn(savedLedger);
        when(balanceService.getCurrentBalance(account)).thenReturn(new BigDecimal("3000.00"));

        // 10 shares @ 100 + 10 shares @ 200 = 20 shares @ 150
        SettlementRequest request = new SettlementRequest(
                UUID.randomUUID(), UUID.randomUUID(), INSTRUMENT_ID, "AAPL", "BUY",
                10, new BigDecimal("200.00"), OffsetDateTime.now());

        SettlementResponse response = service.settleOrder(ACCOUNT_ID, request);

        assertEquals(20L, response.positionQuantity());
        assertEquals(0, new BigDecimal("150.000000").compareTo(response.positionAvgCost()));
        verify(positionRepository).save(existing);
        assertEquals(20L, existing.getQuantity());
    }

    @Test
    @DisplayName("Settle SELL order reduces position quantity")
    void testSettleSellOrder_success() {
        when(accountAuthorizationService.getAuthorizedTradingAccountForUpdate(ACCOUNT_ID)).thenReturn(account);
        Position existing = new Position(ACCOUNT_ID, INSTRUMENT_ID, 25L, new BigDecimal("100.00"), OffsetDateTime.now());
        when(positionRepository.findByIdForUpdate(ACCOUNT_ID, INSTRUMENT_ID)).thenReturn(Optional.of(existing));
        CashLedgerEntry savedLedger = new CashLedgerEntry(
                ACCOUNT_ID, UUID.randomUUID(), UUID.randomUUID(), "SELL_SETTLEMENT",
                new BigDecimal("1500.00"), "USD", OffsetDateTime.now(), "Sell settlement");
        when(cashLedgerRepository.save(any(CashLedgerEntry.class))).thenReturn(savedLedger);
        when(balanceService.getCurrentBalance(account)).thenReturn(new BigDecimal("6500.00"));

        SettlementRequest request = new SettlementRequest(
                UUID.randomUUID(), UUID.randomUUID(), INSTRUMENT_ID, "AAPL", "SELL",
                10, new BigDecimal("150.00"), OffsetDateTime.now());

        SettlementResponse response = service.settleOrder(ACCOUNT_ID, request);

        assertEquals(15L, response.positionQuantity());
        assertEquals(new BigDecimal("100.00"), response.positionAvgCost());
        verify(positionRepository).save(existing);
        assertEquals(15L, existing.getQuantity());
    }

    @Test
    @DisplayName("Settle SELL order throws IllegalStateException if position does not exist")
    void testSettleSellOrder_positionDoesNotExist() {
        when(accountAuthorizationService.getAuthorizedTradingAccountForUpdate(ACCOUNT_ID)).thenReturn(account);
        when(positionRepository.findByIdForUpdate(ACCOUNT_ID, INSTRUMENT_ID)).thenReturn(Optional.empty());

        SettlementRequest request = new SettlementRequest(
                UUID.randomUUID(), UUID.randomUUID(), INSTRUMENT_ID, "AAPL", "SELL",
                10, new BigDecimal("150.00"), OffsetDateTime.now());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.settleOrder(ACCOUNT_ID, request));
        assertEquals("Cannot settle sell: position does not exist", ex.getMessage());
        verify(positionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Settle SELL order throws IllegalStateException if position has insufficient quantity")
    void testSettleSellOrder_insufficientQuantity() {
        when(accountAuthorizationService.getAuthorizedTradingAccountForUpdate(ACCOUNT_ID)).thenReturn(account);
        Position existing = new Position(ACCOUNT_ID, INSTRUMENT_ID, 5L, new BigDecimal("100.00"), OffsetDateTime.now());
        when(positionRepository.findByIdForUpdate(ACCOUNT_ID, INSTRUMENT_ID)).thenReturn(Optional.of(existing));

        SettlementRequest request = new SettlementRequest(
                UUID.randomUUID(), UUID.randomUUID(), INSTRUMENT_ID, "AAPL", "SELL",
                10, new BigDecimal("150.00"), OffsetDateTime.now());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.settleOrder(ACCOUNT_ID, request));
        assertEquals("Cannot settle sell: insufficient position quantity", ex.getMessage());
        verify(positionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Settling an execution that is already on the ledger returns the booked state without booking it again")
    void testSettleOrder_alreadySettled_isNotBookedTwice() {
        when(accountAuthorizationService.getAuthorizedTradingAccountForUpdate(ACCOUNT_ID)).thenReturn(account);
        UUID executionId = UUID.randomUUID();
        CashLedgerEntry booked = new CashLedgerEntry(
                ACCOUNT_ID, UUID.randomUUID(), executionId, "BUY_SETTLEMENT",
                new BigDecimal("-1500.00"), "USD", OffsetDateTime.now(), "Buy settlement");
        when(cashLedgerRepository.findFirstByExecutionId(executionId)).thenReturn(Optional.of(booked));
        when(positionRepository.findById(new PositionId(ACCOUNT_ID, INSTRUMENT_ID))).thenReturn(Optional.of(
                new Position(ACCOUNT_ID, INSTRUMENT_ID, 10L, new BigDecimal("150.00"), OffsetDateTime.now())));
        when(balanceService.getCurrentBalance(account)).thenReturn(new BigDecimal("3500.00"));

        SettlementResponse response = service.settleOrder(ACCOUNT_ID, new SettlementRequest(
                UUID.randomUUID(), executionId, INSTRUMENT_ID, "AAPL", "BUY",
                10, new BigDecimal("150.00"), OffsetDateTime.now()));

        assertEquals(10L, response.positionQuantity());
        assertEquals(new BigDecimal("3500.00"), response.balanceAfter());
        verify(cashLedgerRepository, never()).save(any(CashLedgerEntry.class));
        verify(positionRepository, never()).save(any(Position.class));
    }
}
