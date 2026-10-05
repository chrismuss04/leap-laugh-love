package com.leap.leaplaughlove.marketdata.history;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PriceCandleRetention pruning and tier parsing")
class PriceCandleRetentionPruneTest {

    private final PriceCandleRepository repository = mock(PriceCandleRepository.class);

    @Test
    @DisplayName("deletes each width's candles older than its window")
    void prunesEveryTier() {
        when(repository.deleteByBucketSecondsAndBucketStartBefore(eq(60), any(OffsetDateTime.class))).thenReturn(5);
        when(repository.deleteByBucketSecondsAndBucketStartBefore(eq(300), any(OffsetDateTime.class))).thenReturn(0);
        OffsetDateTime before = OffsetDateTime.now();

        new PriceCandleRetention(repository, List.of("60:2", "300:7")).prune();

        verify(repository).deleteByBucketSecondsAndBucketStartBefore(eq(60), any(OffsetDateTime.class));
        verify(repository).deleteByBucketSecondsAndBucketStartBefore(eq(300), any(OffsetDateTime.class));
        assertEquals(true, before.isBefore(OffsetDateTime.now().plusSeconds(1)));
    }

    @Test
    @DisplayName("prunes nothing quietly when nothing has aged out")
    void nothingToPrune() {
        new PriceCandleRetention(repository, List.of("86400:365")).prune();

        verify(repository).deleteByBucketSecondsAndBucketStartBefore(eq(86400), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("refuses an empty, malformed, non-numeric or non-positive tier list")
    void rejectsBadTiers() {
        assertThrows(IllegalArgumentException.class, () -> CandleTier.parse(List.of()));
        assertThrows(IllegalArgumentException.class, () -> CandleTier.parse(List.of("60")));
        assertThrows(IllegalArgumentException.class, () -> CandleTier.parse(List.of("60:2:3")));
        assertThrows(IllegalArgumentException.class, () -> CandleTier.parse(List.of("sixty:2")));
        assertThrows(IllegalArgumentException.class, () -> CandleTier.parse(List.of("0:2")));
        assertThrows(IllegalArgumentException.class, () -> CandleTier.parse(List.of("60:-1")));
    }

    @Test
    @DisplayName("parses well-formed tiers in the order given, ignoring spaces")
    void parsesTiers() {
        assertEquals(List.of(new CandleTier(60, 2), new CandleTier(3600, 90)),
                CandleTier.parse(List.of(" 60 : 2 ", "3600:90")));
    }
}
