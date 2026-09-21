package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
import com.leap.leaplaughlove.trading.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.trading.position.Position;
import com.leap.leaplaughlove.trading.position.PositionId;
import com.leap.leaplaughlove.trading.position.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TradeValidationService Tests")
class TradeValidationServiceTest {

    @Mock private CashLedgerRepository cashLedgerRepository;
    @Mock private PositionRepository positionRepository;

    private TradeValidationService tradeValidationService;

    private Account activeAccount;
    private Account lockedAccount;
    private Instrument tradableInstrument;
    private Instrument untradableInstrument;

    @BeforeEach
    void setUp() {
        tradeValidationService = new TradeValidationService(cashLedgerRepository, positionRepository);

        UUID clientId = UUID.randomUUID();
        activeAccount = new Account(UUID.randomUUID(), clientId, "ACC-01", "ACTIVE", "USD", true, OffsetDateTime.now());
        lockedAccount = new Account(UUID.randomUUID(), clientId, "ACC-LOCKED", "ACTIVE", "USD", false, OffsetDateTime.now());

        tradableInstrument = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
        untradableInstrument = new Instrument(UUID.randomUUID(), "DELIST", "Delisted Inc.", "EQUITY", "NASDAQ", "USD", false);
    }

    @Test
    @DisplayName("BUY with sufficient cash is ACCEPTED")
    void testBuyWithSufficientCash_Accepted() {
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(activeAccount.getAccountId(), "USD"))
                .thenReturn(new BigDecimal("10000.00"));

        TradeValidationResult result = tradeValidationService.validateTrade(
                activeAccount, tradableInstrument, Order.Side.BUY, 10, new BigDecimal("150.00"));

        assertTrue(result.isValid());
        assertNull(result.reason());
    }

    @Test
    @DisplayName("BUY with insufficient cash is REJECTED")
    void testBuyWithInsufficientCash_Rejected() {
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(activeAccount.getAccountId(), "USD"))
                .thenReturn(new BigDecimal("500.00"));

        TradeValidationResult result = tradeValidationService.validateTrade(
                activeAccount, tradableInstrument, Order.Side.BUY, 10, new BigDecimal("150.00"));

        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Insufficient funds"));
    }

    @Test
    @DisplayName("SELL with sufficient position is ACCEPTED")
    void testSellWithSufficientPosition_Accepted() {
        Position position = new Position(activeAccount.getAccountId(), tradableInstrument.getInstrumentId(),
                20, new BigDecimal("140.00"), OffsetDateTime.now());
        when(positionRepository.findById(new PositionId(activeAccount.getAccountId(), tradableInstrument.getInstrumentId())))
                .thenReturn(Optional.of(position));

        TradeValidationResult result = tradeValidationService.validateTrade(
                activeAccount, tradableInstrument, Order.Side.SELL, 15, new BigDecimal("150.00"));

        assertTrue(result.isValid());
        assertNull(result.reason());
    }

    @Test
    @DisplayName("SELL with insufficient position is REJECTED")
    void testSellWithInsufficientPosition_Rejected() {
        Position position = new Position(activeAccount.getAccountId(), tradableInstrument.getInstrumentId(),
                5, new BigDecimal("140.00"), OffsetDateTime.now());
        when(positionRepository.findById(new PositionId(activeAccount.getAccountId(), tradableInstrument.getInstrumentId())))
                .thenReturn(Optional.of(position));

        TradeValidationResult result = tradeValidationService.validateTrade(
                activeAccount, tradableInstrument, Order.Side.SELL, 10, new BigDecimal("150.00"));

        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Insufficient position quantity"));
    }

    @Test
    @DisplayName("SELL without existing position is REJECTED")
    void testSellWithNoPosition_Rejected() {
        when(positionRepository.findById(new PositionId(activeAccount.getAccountId(), tradableInstrument.getInstrumentId())))
                .thenReturn(Optional.empty());

        TradeValidationResult result = tradeValidationService.validateTrade(
                activeAccount, tradableInstrument, Order.Side.SELL, 10, new BigDecimal("150.00"));

        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Insufficient position quantity"));
    }

    @Test
    @DisplayName("Trade on an inactive account is REJECTED")
    void testInactiveAccount_Rejected() {
        Account inactiveAccount = new Account(UUID.randomUUID(), activeAccount.getClientId(),
                "ACC-INACTIVE", "BLOCKED", "USD", true, OffsetDateTime.now());

        TradeValidationResult result = tradeValidationService.validateTrade(
                inactiveAccount, tradableInstrument, Order.Side.BUY, 10, new BigDecimal("150.00"));

        assertFalse(result.isValid());
        assertEquals("Account is not active", result.reason());
        verifyNoInteractions(cashLedgerRepository, positionRepository);
    }

    @Test
    @DisplayName("Trade on locked/trading-disabled account is REJECTED")
    void testLockedAccount_Rejected() {
        TradeValidationResult result = tradeValidationService.validateTrade(
                lockedAccount, tradableInstrument, Order.Side.BUY, 10, new BigDecimal("150.00"));

        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Trading is disabled"));
    }

    @Test
    @DisplayName("Trade on non-tradable instrument is REJECTED")
    void testNonTradableInstrument_Rejected() {
        TradeValidationResult result = tradeValidationService.validateTrade(
                activeAccount, untradableInstrument, Order.Side.BUY, 10, new BigDecimal("150.00"));

        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Instrument is not tradable"));
    }

    @Test
    @DisplayName("Trade with invalid quantity <= 0 is REJECTED")
    void testInvalidQuantity_Rejected() {
        TradeValidationResult result = tradeValidationService.validateTrade(
                activeAccount, tradableInstrument, Order.Side.BUY, 0, new BigDecimal("150.00"));

        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Quantity must be greater than zero"));
    }
}

