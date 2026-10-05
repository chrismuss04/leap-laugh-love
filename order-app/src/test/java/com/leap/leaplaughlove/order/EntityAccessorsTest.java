package com.leap.leaplaughlove.order;

import com.leap.leaplaughlove.order.account.Account;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.position.PositionMovement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Order entity accessors and state transitions")
class EntityAccessorsTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-01-01T10:00:00Z");

    private final UUID accountId = UUID.randomUUID();
    private final UUID instrumentId = UUID.randomUUID();
    private final Instrument instrument =
            new Instrument(instrumentId, "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);

    @Test
    @DisplayName("an instrument exposes the values it was built with")
    void instrument() {
        assertEquals(instrumentId, instrument.getInstrumentId());
        assertEquals("AAPL", instrument.getSymbol());
        assertEquals("Apple Inc.", instrument.getInstrumentName());
        assertEquals("EQUITY", instrument.getAssetClass());
        assertEquals("NASDAQ", instrument.getMarket());
        assertEquals("USD", instrument.getCurrency());
        assertTrue(instrument.isTradable());
        assertNotNull(instrument.getCreatedAt());
    }

    @Test
    @DisplayName("an account exposes the values it was built with")
    void account() {
        UUID clientId = UUID.randomUUID();

        Account account = new Account(accountId, clientId, "ACC-0001", "ACTIVE", "USD", true, AT);

        assertEquals(accountId, account.getAccountId());
        assertEquals(clientId, account.getClientId());
        assertEquals("ACC-0001", account.getAccountNumber());
        assertEquals("ACTIVE", account.getStatus());
        assertEquals("USD", account.getBaseCurrency());
        assertTrue(account.isTradingEnabled());
        assertEquals(AT, account.getCreatedAt());
    }

    @Test
    @DisplayName("a position movement exposes its values and defaults a missing cost and time")
    void positionMovement() {
        UUID orderId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        PositionMovement movement = new PositionMovement(accountId, instrumentId, orderId, executionId,
                "BUY", 5, new BigDecimal("12.50"), AT);

        assertNull(movement.getMovementId());
        assertEquals(accountId, movement.getAccountId());
        assertEquals(instrumentId, movement.getInstrumentId());
        assertEquals(orderId, movement.getOrderId());
        assertEquals(executionId, movement.getExecutionId());
        assertEquals("BUY", movement.getMovementType());
        assertEquals(5, movement.getQuantityDelta());
        assertEquals(new BigDecimal("12.50"), movement.getCostDelta());
        assertEquals(AT, movement.getCreatedAt());

        PositionMovement defaulted = new PositionMovement(accountId, instrumentId, null, null, "SELL", -1, null, null);
        assertEquals(BigDecimal.ZERO, defaulted.getCostDelta());
        assertNotNull(defaulted.getCreatedAt());
    }

    @Test
    @DisplayName("a new order is submitted, defaulting its time")
    void newOrder() {
        Order order = new Order(accountId, instrument, Order.Side.BUY, 3L, null);

        assertEquals(Order.Status.SUBMITTED, order.getStatus());
        assertEquals(accountId, order.getAccountId());
        assertSame(instrument, order.getInstrument());
        assertEquals(Order.Side.BUY, order.getSide());
        assertEquals(3L, order.getQuantity());
        assertNotNull(order.getSubmittedAt());
        assertNull(order.getOrderId());
        assertNull(order.getQuotedPrice());
        assertNull(order.getMaxSlippagePercent());
        assertEquals(AT, new Order(accountId, instrument, Order.Side.SELL, 1L, AT).getSubmittedAt());
    }

    @Test
    @DisplayName("an order built from an account takes the account's id, or none")
    void orderFromAccount() {
        Account account = new Account(accountId, UUID.randomUUID(), "ACC-0001", "ACTIVE", "USD", true, AT);
        UUID orderId = UUID.randomUUID();

        Order order = new Order(orderId, account, instrument, Order.Side.SELL, 2L, Order.Status.FILLED,
                AT, AT.plusSeconds(1), null, AT.plusSeconds(2), null);
        Order orphan = new Order(orderId, (Account) null, instrument, Order.Side.SELL, 2L, Order.Status.REJECTED,
                AT, null, AT.plusSeconds(1), null, "no account");

        assertEquals(orderId, order.getOrderId());
        assertEquals(accountId, order.getAccountId());
        assertEquals(AT.plusSeconds(1), order.getAcceptedAt());
        assertEquals(AT.plusSeconds(2), order.getFilledAt());
        assertNull(orphan.getAccountId());
        assertEquals("no account", orphan.getRejectionReason());
        assertEquals(AT.plusSeconds(1), orphan.getRejectedAt());
    }

    @Test
    @DisplayName("an order moves through accepted, rejected and filled, defaulting missing times")
    void transitions() {
        Order order = new Order(accountId, instrument, Order.Side.BUY, 3L, AT);

        order.markAccepted(AT.plusSeconds(1));
        assertEquals(Order.Status.ACCEPTED, order.getStatus());
        assertEquals(AT.plusSeconds(1), order.getAcceptedAt());
        order.markAccepted(null);
        assertNotNull(order.getAcceptedAt());

        order.markFilled(AT.plusSeconds(2));
        assertEquals(Order.Status.FILLED, order.getStatus());
        assertEquals(AT.plusSeconds(2), order.getFilledAt());
        order.markFilled(null);
        assertNotNull(order.getFilledAt());

        order.markRejected("too risky", AT.plusSeconds(3));
        assertEquals(Order.Status.REJECTED, order.getStatus());
        assertEquals("too risky", order.getRejectionReason());
        assertEquals(AT.plusSeconds(3), order.getRejectedAt());
        order.markRejected("again", null);
        assertNotNull(order.getRejectedAt());
    }

    @Test
    @DisplayName("an order's timestamps, reason, number and price tolerance can be set")
    void setters() {
        Order order = new Order(accountId, instrument, Order.Side.BUY, 3L, AT);

        order.setAcceptedAt(AT.plusSeconds(1));
        order.setRejectedAt(AT.plusSeconds(2));
        order.setFilledAt(AT.plusSeconds(3));
        order.setRejectionReason("why");
        order.setAccountNumber("ACC-0001");
        order.setPriceTolerance(new BigDecimal("100.50"), new BigDecimal("2.00"));

        assertEquals(AT.plusSeconds(1), order.getAcceptedAt());
        assertEquals(AT.plusSeconds(2), order.getRejectedAt());
        assertEquals(AT.plusSeconds(3), order.getFilledAt());
        assertEquals("why", order.getRejectionReason());
        assertEquals("ACC-0001", order.getAccountNumber());
        assertEquals(new BigDecimal("100.50"), order.getQuotedPrice());
        assertEquals(new BigDecimal("2.00"), order.getMaxSlippagePercent());
    }
}
