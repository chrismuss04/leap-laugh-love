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

    // Verify missing account data rejects a trade instead of allowing execution.
    @Test
    void rejectsMissingAccount() {
        var result = tradeValidationService.validateTrade(null, tradableInstrument, Order.Side.BUY, 1, BigDecimal.ONE);
        assertFalse(result.isValid());
        assertEquals("Account is not active", result.reason());
    }

    // Verify a missing instrument cannot be traded.
    @Test
    void rejectsMissingInstrument() {
        var result = tradeValidationService.validateTrade(activeAccount, null, Order.Side.BUY, 1, BigDecimal.ONE);
        assertFalse(result.isValid());
        assertEquals("Instrument is not tradable", result.reason());
    }

    // Verify null, zero, and negative prices reject both buy and sell orders.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "-0.01"})
    void rejectsInvalidPrices(String value) {
        var price = value == null ? null : new BigDecimal(value);
        for (var side : new Order.Side[]{Order.Side.BUY, Order.Side.SELL}) {
            var result = tradeValidationService.validateTrade(activeAccount, tradableInstrument, side, 1, price);
            assertFalse(result.isValid());
            assertEquals("Price must be greater than zero", result.reason());
        }
    }

    // Verify a buy can use the exact available balance after rounding its cost to cents.
    @Test
    void acceptsExactCash() {
        var account = new AccountValidationDto(true, true, new BigDecimal("10.01"), 0, "USD", "ACC-01", null);
        var result = tradeValidationService.validateTrade(account, tradableInstrument, Order.Side.BUY, 1, new BigDecimal("10.005"));
        assertTrue(result.isValid());
        assertNull(result.reason());
    }

    // Verify rounding up cannot allow a buy that exceeds cash by one cent.
    @Test
    void rejectsRoundedOverdraft() {
        var account = new AccountValidationDto(true, true, new BigDecimal("10.00"), 0, "USD", "ACC-01", null);
        var result = tradeValidationService.validateTrade(account, tradableInstrument, Order.Side.BUY, 1, new BigDecimal("10.005"));
        assertFalse(result.isValid());
        assertEquals("Insufficient funds - order rejected", result.reason());
    }

    // Verify unavailable cash data cannot authorize a buy.
    @Test
    void rejectsMissingCash() {
        var account = new AccountValidationDto(true, true, null, 20, "USD", "ACC-01", null);
        var result = tradeValidationService.validateTrade(account, tradableInstrument, Order.Side.BUY, 1, BigDecimal.ONE);
        assertFalse(result.isValid());
        assertEquals("Insufficient funds - order rejected", result.reason());
    }

    // Verify selling exactly the owned shares is permitted even with no available cash.
    @Test
    void acceptsAllShares() {
        var account = new AccountValidationDto(true, true, BigDecimal.ZERO, 20, "USD", "ACC-01", null);
        var result = tradeValidationService.validateTrade(account, tradableInstrument, Order.Side.SELL, 20, BigDecimal.ONE);
        assertTrue(result.isValid());
        assertNull(result.reason());
    }

    // Verify an unspecified order side is rejected rather than treated as a buy or sell.
    @Test
    void rejectsMissingSide() {
        var result = tradeValidationService.validateTrade(activeAccount, tradableInstrument, null, 1, BigDecimal.ONE);
        assertFalse(result.isValid());
        assertEquals("Unsupported order side: null", result.reason());
    }

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
                true, true, new BigDecimal("10000.00"), 20, "USD", "ACC-01", null);
        lockedAccount = new AccountValidationDto(
                true, false, new BigDecimal("10000.00"), 20, "USD", "ACC-LOCKED", null);
        inactiveAccount = new AccountValidationDto(
                false, true, new BigDecimal("10000.00"), 20, "USD", "ACC-INACTIVE", null);

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
                true, true, new BigDecimal("500.00"), 20, "USD", "ACC-01", null);

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
                true, true, new BigDecimal("10000.00"), 0, "USD", "ACC-01", null);

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

    private TradeValidationResult tolerance(String quoted, String execution, String maxPercent) {
        return tradeValidationService.checkPriceTolerance(
                new BigDecimal(quoted), new BigDecimal(execution), new BigDecimal(maxPercent));
    }

    @Test
    @DisplayName("Price tolerance: a move within the tolerance is ACCEPTED, up or down")
    void testPriceTolerance_MoveWithinTolerance_Accepted() {
        assertTrue(tolerance("100.00", "100.9900", "1.00").isValid());
        assertTrue(tolerance("100.00", "99.0100", "1.00").isValid());
    }

    @Test
    @DisplayName("Price tolerance: a move of exactly the tolerance is ACCEPTED, up or down")
    void testPriceTolerance_MoveEqualToTolerance_Accepted() {
        assertTrue(tolerance("100.00", "101.0000", "1.00").isValid());
        assertTrue(tolerance("100.00", "99.0000", "1.00").isValid());
    }

    @Test
    @DisplayName("Price tolerance: a rise beyond the tolerance is REJECTED with the size of the move")
    void testPriceTolerance_RiseBeyondTolerance_Rejected() {
        TradeValidationResult result = tolerance("150.00", "151.8500", "1.00");

        assertFalse(result.isValid());
        assertEquals("Price moved 1.23% from your quoted $150.00 to $151.85, beyond your 1.00% tolerance"
                + " - order rejected", result.reason());
    }

    @Test
    @DisplayName("Price tolerance: a fall beyond the tolerance is REJECTED too, even though it favours a buyer")
    void testPriceTolerance_FallBeyondTolerance_Rejected() {
        TradeValidationResult result = tolerance("150.00", "148.0000", "1.00");

        assertFalse(result.isValid());
        assertTrue(result.reason().startsWith("Price moved 1.33% from your quoted $150.00 to $148.00"));
    }

    @Test
    @DisplayName("Price tolerance: a zero tolerance accepts only an unchanged price")
    void testPriceTolerance_ZeroTolerance() {
        assertTrue(tolerance("150.00", "150.0000", "0").isValid());
        assertFalse(tolerance("150.00", "150.0100", "0").isValid());
    }
}
