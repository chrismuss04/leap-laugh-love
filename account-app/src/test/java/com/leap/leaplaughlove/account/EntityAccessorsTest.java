package com.leap.leaplaughlove.account;

import com.leap.leaplaughlove.account.client.ClientStatus;
import com.leap.leaplaughlove.account.instrument.Instrument;
import com.leap.leaplaughlove.account.position.PositionId;
import com.leap.leaplaughlove.account.position.PositionMovement;
import com.leap.leaplaughlove.account.quote.QuoteUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

@DisplayName("Account entity and exception accessors")
class EntityAccessorsTest {

    private final UUID accountId = UUID.randomUUID();
    private final UUID instrumentId = UUID.randomUUID();

    @Test
    @DisplayName("an instrument exposes the values it was built with")
    void instrument() {
        Instrument instrument = new Instrument(instrumentId, "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);

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
    @DisplayName("a client status exposes its id and status")
    void clientStatus() {
        ClientStatus status = new ClientStatus(accountId, "ACTIVE");

        assertEquals(accountId, status.getClientId());
        assertEquals("ACTIVE", status.getStatus());
    }

    @Test
    @DisplayName("position ids are equal when both account and instrument match")
    void positionId() {
        PositionId id = new PositionId(accountId, instrumentId);

        assertEquals(accountId, id.getAccountId());
        assertEquals(instrumentId, id.getInstrumentId());
        assertEquals(id, id);
        assertEquals(id, new PositionId(accountId, instrumentId));
        assertEquals(id.hashCode(), new PositionId(accountId, instrumentId).hashCode());
        assertNotEquals(id, new PositionId(UUID.randomUUID(), instrumentId));
        assertNotEquals(id, new PositionId(accountId, UUID.randomUUID()));
        assertNotEquals(id, null);
        assertNotEquals(id, "not an id");
        assertNull(new PositionId().getAccountId());
    }

    @Test
    @DisplayName("a position movement exposes its values and defaults a missing cost and time")
    void positionMovement() {
        UUID orderId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.parse("2026-01-01T10:00:00Z");

        PositionMovement movement = new PositionMovement(accountId, instrumentId, orderId, executionId,
                "BUY", 5, new BigDecimal("12.50"), at);

        assertNull(movement.getMovementId());
        assertEquals(accountId, movement.getAccountId());
        assertEquals(instrumentId, movement.getInstrumentId());
        assertEquals(orderId, movement.getOrderId());
        assertEquals(executionId, movement.getExecutionId());
        assertEquals("BUY", movement.getMovementType());
        assertEquals(5, movement.getQuantityDelta());
        assertEquals(new BigDecimal("12.50"), movement.getCostDelta());
        assertEquals(at, movement.getCreatedAt());

        PositionMovement defaulted = new PositionMovement(accountId, instrumentId, null, null, "SELL", -1, null, null);
        assertEquals(BigDecimal.ZERO, defaulted.getCostDelta());
        assertNotNull(defaulted.getCreatedAt());
    }

    @Test
    @DisplayName("a quote-unavailable exception keeps its message and cause")
    void quoteUnavailable() {
        RuntimeException cause = new RuntimeException("boom");

        assertEquals("plain", new QuoteUnavailableException("plain").getMessage());
        assertSame(cause, new QuoteUnavailableException("wrapped", cause).getCause());
        assertFalse(new QuoteUnavailableException("x").getMessage().isEmpty());
    }

    @Test
    @DisplayName("main starts the Spring application")
    void mainRunsTheApplication() {
        try (MockedStatic<SpringApplication> spring = mockStatic(SpringApplication.class)) {
            String[] args = {"--spring.profiles.active=test"};

            AccountApplication.main(args);

            spring.verify(() -> SpringApplication.run(any(Class.class), any(String[].class)));
        }
    }
}
