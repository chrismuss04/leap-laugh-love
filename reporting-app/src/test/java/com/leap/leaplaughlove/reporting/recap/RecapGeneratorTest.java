package com.leap.leaplaughlove.reporting.recap;

import com.leap.leaplaughlove.reporting.recap.RecapFill.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("RecapGenerator")
class RecapGeneratorTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final Instant NOW = Instant.parse("2026-10-01T00:30:00Z");
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID WINNER = new UUID(0, 1);
    private static final UUID LOSER = new UUID(0, 2);
    private static final UUID HOLDER = new UUID(0, 3);

    private RecapFillRepository fills;
    private RecapSnapshotRepository snapshots;
    private RecapPrices prices;
    private RecapGenerator generator;

    @BeforeEach
    void setUp() {
        fills = mock(RecapFillRepository.class);
        snapshots = mock(RecapSnapshotRepository.class);
        prices = mock(RecapPrices.class);
        generator = new RecapGenerator(fills, snapshots, prices, new RecapCalculator(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(fills.sectors()).thenReturn(Map.of("AAPL", "Information Technology"));
    }

    private static RecapFill fill(String symbol, Side side, long quantity, String price, String filledAt) {
        return new RecapFill(ACCOUNT, symbol, side, quantity, new BigDecimal(price), OffsetDateTime.parse(filledAt));
    }

    @Test
    @DisplayName("builds every client's recap, ranks them against each other, and stores the month at once")
    void buildsRanksAndStores() {
        Map<UUID, List<RecapFill>> byClient = new LinkedHashMap<>();
        // Up 20% on AAPL; down 20% on MSFT; held AAPL from August without trading in September.
        byClient.put(WINNER, List.of(fill("AAPL", Side.BUY, 10, "100", "2026-09-02T14:00:00Z")));
        byClient.put(LOSER, List.of(fill("MSFT", Side.BUY, 10, "100", "2026-09-02T14:00:00Z")));
        byClient.put(HOLDER, List.of(fill("AAPL", Side.BUY, 1, "100", "2026-08-03T14:00:00Z"),
                fill("TSLA", Side.BUY, 1, "100", "2026-07-03T14:00:00Z"),
                fill("TSLA", Side.SELL, 1, "100", "2026-07-04T14:00:00Z")));
        when(fills.fillsThrough(SEP)).thenReturn(byClient);
        when(prices.load(eq(SEP), any())).thenReturn(new PriceBook(Map.of(
                "AAPL", Map.of(LocalDate.of(2026, 8, 31), new BigDecimal("100"), LocalDate.of(2026, 9, 30), new BigDecimal("120")),
                "MSFT", Map.of(LocalDate.of(2026, 9, 30), new BigDecimal("80")))));

        assertEquals(3, generator.generate(SEP));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, RecapPayload>> stored = ArgumentCaptor.forClass(Map.class);
        verify(snapshots).saveMonth(eq(SEP), stored.capture(), eq(NOW.atOffset(ZoneOffset.UTC)));
        Map<UUID, RecapPayload> recaps = stored.getValue();
        // WINNER and HOLDER both made 20%, so they share the top third; LOSER is outside the top half.
        assertEquals(34, recaps.get(WINNER).topPercent());
        assertEquals(34, recaps.get(HOLDER).topPercent());
        assertNull(recaps.get(LOSER).topPercent());
        assertTrue(recaps.get(HOLDER).quiet(), "A holder who didn't trade still gets a recap");
        // TSLA was closed out in July, so it isn't priced.
        verify(prices).load(SEP, Set.of("AAPL", "MSFT"));
    }

    @Test
    @DisplayName("stores nothing and fetches no prices when nobody has traded yet")
    void nobodyTraded() {
        when(fills.fillsThrough(SEP)).thenReturn(Map.of());

        assertEquals(0, generator.generate(SEP));

        verifyNoInteractions(prices, snapshots);
    }

    @Test
    @DisplayName("stores nothing when market data fails, so the month is tried again whole")
    void outage() {
        when(fills.fillsThrough(SEP)).thenReturn(
                Map.of(WINNER, List.of(fill("AAPL", Side.BUY, 1, "100", "2026-09-02T14:00:00Z"))));
        when(prices.load(eq(SEP), any())).thenThrow(new RecapPricesUnavailableException("down", null));

        assertThrows(RecapPricesUnavailableException.class, () -> generator.generate(SEP));

        verify(snapshots, never()).saveMonth(any(), anyMap(), any());
    }
}
