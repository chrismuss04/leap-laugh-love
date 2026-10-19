package com.leap.leaplaughlove.reporting.recap;

import com.leap.leaplaughlove.reporting.recap.RecapFill.Side;
import com.leap.leaplaughlove.reporting.recap.RecapSnapshotRepository.StoredRecap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("RecapService")
class RecapServiceTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    // Mid-October: last month is September.
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final OffsetDateTime GENERATED = OffsetDateTime.parse("2026-10-01T00:30:00Z");
    private static final UUID CLIENT = UUID.randomUUID();

    private RecapSnapshotRepository snapshots;
    private RecapFillRepository fills;
    private RecapPrices prices;
    private RecapService service;
    private final RecapCalculator calculator = new RecapCalculator();

    @BeforeEach
    void setUp() {
        snapshots = mock(RecapSnapshotRepository.class);
        fills = mock(RecapFillRepository.class);
        prices = mock(RecapPrices.class);
        service = new RecapService(snapshots, fills, prices, calculator, Clock.fixed(NOW, ZoneOffset.UTC));
        when(snapshots.find(any(), any())).thenReturn(Optional.empty());
        when(fills.sectors()).thenReturn(Map.of());
    }

    @Test
    @DisplayName("serves last month's stored recap, ranked")
    void stored() {
        RecapPayload payload = calculator.calculate(SEP, List.of(), PriceBook.empty(), Map.of()).withTopPercent(4);
        StoredRecap stored = new StoredRecap(SEP, RecapCalculator.SCHEMA_VERSION, "{}", GENERATED);
        when(snapshots.find(CLIENT, SEP)).thenReturn(Optional.of(stored));
        when(snapshots.payload(stored)).thenReturn(payload);

        RecapResponse response = service.latest(CLIENT);

        assertEquals(SEP, response.month());
        assertEquals(GENERATED, response.generatedAt());
        assertTrue(response.ranked());
        assertSame(payload, response.recap());
        verifyNoInteractions(fills, prices);
    }

    @Test
    @DisplayName("works out the recap for the request when the month's job hasn't run, unranked and unstored")
    void onDemand() {
        when(fills.fillsThrough(CLIENT, SEP)).thenReturn(List.of(new RecapFill(UUID.randomUUID(), "AAPL", Side.BUY, 10,
                new BigDecimal("100"), OffsetDateTime.parse("2026-09-02T14:00:00Z"))));
        when(prices.load(SEP, Set.of("AAPL"))).thenReturn(new PriceBook(
                Map.of("AAPL", Map.of(LocalDate.of(2026, 9, 30), new BigDecimal("110")))));

        RecapResponse response = service.latest(CLIENT);

        assertFalse(response.ranked());
        assertEquals(NOW.atOffset(ZoneOffset.UTC), response.generatedAt());
        assertEquals(new BigDecimal("10.00"), response.recap().tradingReturn().returnPct());
        assertNull(response.recap().topPercent());
        verify(snapshots, never()).saveMonth(any(), any(), any());
    }

    @Test
    @DisplayName("works a recap stored in another JSON shape out again rather than misreading it")
    void otherShape() {
        when(snapshots.find(CLIENT, SEP)).thenReturn(Optional.of(
                new StoredRecap(SEP, RecapCalculator.SCHEMA_VERSION + 1, "{}", GENERATED)));
        when(prices.load(eq(SEP), any())).thenReturn(PriceBook.empty());

        assertFalse(service.latest(CLIENT).ranked());
        verify(snapshots, never()).payload(any());
    }

    @Test
    @DisplayName("gives a client who has never traded a quiet recap")
    void neverTraded() {
        when(fills.fillsThrough(CLIENT, SEP)).thenReturn(List.of());
        when(prices.load(SEP, Set.of())).thenReturn(PriceBook.empty());

        RecapPayload recap = service.latest(CLIENT).recap();

        assertTrue(recap.quiet());
        assertEquals(0, recap.moneyMoved().filledOrders());
    }
}
