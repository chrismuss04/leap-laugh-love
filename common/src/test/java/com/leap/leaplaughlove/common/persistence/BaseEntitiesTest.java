package com.leap.leaplaughlove.common.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shared entity superclasses")
class BaseEntitiesTest {

    static class TestAccount extends BaseAccount {
        TestAccount() {
        }

        TestAccount(UUID accountId, UUID clientId, String accountNumber, String status,
                    String baseCurrency, boolean tradingEnabled, OffsetDateTime createdAt) {
            super(accountId, clientId, accountNumber, status, baseCurrency, tradingEnabled, createdAt);
        }
    }

    static class TestInstrument extends BaseInstrument {
        TestInstrument() {
        }

        TestInstrument(UUID instrumentId, String symbol, String instrumentName, String assetClass,
                       String market, String currency, boolean isTradable) {
            super(instrumentId, symbol, instrumentName, assetClass, market, currency, isTradable);
        }
    }

    static class TestPositionMovement extends BasePositionMovement {
        TestPositionMovement() {
        }

        TestPositionMovement(UUID accountId, UUID instrumentId, UUID orderId, UUID executionId, String movementType,
                             long quantityDelta, BigDecimal costDelta, OffsetDateTime createdAt) {
            super(accountId, instrumentId, orderId, executionId, movementType, quantityDelta, costDelta, createdAt);
        }
    }

    @Test
    @DisplayName("BaseAccount exposes every constructor value")
    void account() {
        UUID accountId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");

        BaseAccount account = new TestAccount(accountId, clientId, "ACC-1", "ACTIVE", "USD", true, createdAt);

        assertEquals(accountId, account.getAccountId());
        assertEquals(clientId, account.getClientId());
        assertEquals("ACC-1", account.getAccountNumber());
        assertEquals("ACTIVE", account.getStatus());
        assertEquals("USD", account.getBaseCurrency());
        assertTrue(account.isTradingEnabled());
        assertEquals(createdAt, account.getCreatedAt());
        assertNull(new TestAccount().getAccountId());
    }

    @Test
    @DisplayName("BaseInstrument exposes every constructor value and stamps its creation time")
    void instrument() {
        UUID instrumentId = UUID.randomUUID();

        BaseInstrument instrument = new TestInstrument(instrumentId, "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", false);

        assertEquals(instrumentId, instrument.getInstrumentId());
        assertEquals("AAPL", instrument.getSymbol());
        assertEquals("Apple Inc.", instrument.getInstrumentName());
        assertEquals("EQUITY", instrument.getAssetClass());
        assertEquals("NASDAQ", instrument.getMarket());
        assertEquals("USD", instrument.getCurrency());
        assertFalse(instrument.isTradable());
        assertNotNull(instrument.getCreatedAt());
    }

    @Test
    @DisplayName("BaseInstrument defaults to a tradable NASDAQ instrument in USD")
    void instrumentDefaults() {
        BaseInstrument instrument = new TestInstrument();

        assertEquals("NASDAQ", instrument.getMarket());
        assertEquals("USD", instrument.getCurrency());
        assertTrue(instrument.isTradable());
        assertNull(instrument.getCreatedAt());
    }

    @Test
    @DisplayName("BasePositionMovement exposes every constructor value")
    void positionMovement() {
        UUID accountId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");

        BasePositionMovement movement = new TestPositionMovement(accountId, instrumentId, orderId, executionId,
                "BUY", 10, new BigDecimal("1500.00"), createdAt);

        assertNull(movement.getMovementId());
        assertEquals(accountId, movement.getAccountId());
        assertEquals(instrumentId, movement.getInstrumentId());
        assertEquals(orderId, movement.getOrderId());
        assertEquals(executionId, movement.getExecutionId());
        assertEquals("BUY", movement.getMovementType());
        assertEquals(10, movement.getQuantityDelta());
        assertEquals(new BigDecimal("1500.00"), movement.getCostDelta());
        assertEquals(createdAt, movement.getCreatedAt());
    }

    @Test
    @DisplayName("BasePositionMovement defaults a missing cost to zero and a missing time to now")
    void positionMovementDefaults() {
        OffsetDateTime before = OffsetDateTime.now();

        BasePositionMovement movement = new TestPositionMovement(UUID.randomUUID(), UUID.randomUUID(), null, null,
                "SELL", -5, null, null);

        assertEquals(BigDecimal.ZERO, movement.getCostDelta());
        assertFalse(movement.getCreatedAt().isBefore(before));
        assertNull(new TestPositionMovement().getCostDelta());
    }
}
