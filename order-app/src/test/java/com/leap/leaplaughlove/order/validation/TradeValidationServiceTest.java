package com.leap.leaplaughlove.order.validation;

import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.order.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TradeValidationService Tests")
class TradeValidationServiceTest {

    private TradeValidationService tradeValidationService;

    private AccountValidationDto activeAccount;
    private AccountValidationDto lockedAccount;
    private AccountValidationDto inactiveAccount;
    private Instrument tradableInstrument;
    private Instrument untradableInstrument;

    @BeforeEach
    void setUp() {
        tradeValidationService = new TradeValidationService();

        activeAccount = new AccountValidationDto(
                true, true, new BigDecimal("10000.00"), 20, "USD", "ACC-01");
        lockedAccount = new AccountValidationDto(
                true, false, new BigDecimal("10000.00"), 20, "USD", "ACC-LOCKED");
        inactiveAccount = new AccountValidationDto(
                false, true, new BigDecimal("10000.00"), 20, "USD", "ACC-INACTIVE");

        tradableInstrument = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
        untradableInstrument = new Instrument(UUID.randomUUID(), "DELIST", "Delisted Inc.", "EQUITY", "NASDAQ", "USD", false);
    }

    @Test
    @DisplayName("BUY with sufficient cash is ACCEPTED")
    void testBuyWithSufficientCash_Accepted() {
        TradeValidationResult result = tradeValidationService.validateTrade(
                activeAccount, tradableInstrument, Order.Side.BUY, 10, new BigDecimal("150.00"));

        assertTrue(result.isValid());
        assertNull(result.reason());
    }

    @Test
    @DisplayName("BUY with insufficient cash is REJECTED")
    void testBuyWithInsufficientCash_Rejected() {
        AccountValidationDto lowCashAccount = new AccountValidationDto(
                true, true, new BigDecimal("500.00"), 20, "USD", "ACC-01");

        TradeValidationResult result = tradeValidationService.validateTrade(
                lowCashAccount, tradableInstrument, Order.Side.BUY, 10, new BigDecimal("150.00"));

        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Insufficient funds"));
    }

    @Test
    @DisplayName("SELL with sufficient position is ACCEPTED")
    void testSellWithSufficientPosition_Accepted() {
        TradeValidationResult result = tradeValidationService.validateTrade(
                activeAccount, tradableInstrument, Order.Side.SELL, 15, new BigDecimal("150.00"));

        assertTrue(result.isValid());
        assertNull(result.reason());
    }

    @Test
    @DisplayName("SELL with insufficient position is REJECTED")
    void testSellWithInsufficientPosition_Rejected() {
        TradeValidationResult result = tradeValidationService.validateTrade(
                activeAccount, tradableInstrument, Order.Side.SELL, 25, new BigDecimal("150.00"));

        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Insufficient position quantity"));
    }

    @Test
    @DisplayName("SELL without existing position is REJECTED")
    void testSellWithNoPosition_Rejected() {
        AccountValidationDto noHoldingsAccount = new AccountValidationDto(
                true, true, new BigDecimal("10000.00"), 0, "USD", "ACC-01");

        TradeValidationResult result = tradeValidationService.validateTrade(
                noHoldingsAccount, tradableInstrument, Order.Side.SELL, 10, new BigDecimal("150.00"));

        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Insufficient position quantity"));
    }

    @Test
    @DisplayName("Trade on an inactive account is REJECTED")
    void testInactiveAccount_Rejected() {
        TradeValidationResult result = tradeValidationService.validateTrade(
                inactiveAccount, tradableInstrument, Order.Side.BUY, 10, new BigDecimal("150.00"));

        assertFalse(result.isValid());
        assertEquals("Account is not active", result.reason());
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

