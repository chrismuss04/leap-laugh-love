package com.leap.leaplaughlove.marketdata.history;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PriceCandleRetention Unit Tests")
class PriceCandleRetentionTest {

    @Mock
    private PriceCandleRepository candleRepository;

    private PriceCandleRetention retentionWith(String tiers) {
        return new PriceCandleRetention(candleRepository, List.of(tiers.split(",")));
    }

    @Test
    @DisplayName("each width is pruned to its own cutoff")
    void testPrunesEachWidthToItsOwnCutoff() {
        when(candleRepository.deleteByBucketSecondsAndBucketStartBefore(anyInt(), any())).thenReturn(0);
        OffsetDateTime before = OffsetDateTime.now();

        retentionWith("86400:365,60:2").prune();

        ArgumentCaptor<OffsetDateTime> dayCutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(candleRepository).deleteByBucketSecondsAndBucketStartBefore(eq(86400), dayCutoff.capture());
        ArgumentCaptor<OffsetDateTime> minuteCutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(candleRepository).deleteByBucketSecondsAndBucketStartBefore(eq(60), minuteCutoff.capture());

        // Daily candles are kept far longer than minute candles - that asymmetry is what keeps
        // a year of history affordable, so it is the thing worth pinning.
        assertTrue(dayCutoff.getValue().isBefore(minuteCutoff.getValue()),
                "the 1d cutoff should reach further back than the 60s cutoff");
        assertTrue(dayCutoff.getValue().isBefore(before.minusDays(364)));
        assertTrue(minuteCutoff.getValue().isAfter(before.minusDays(3)));
    }

    @Test
    @DisplayName("a width that is not configured is never pruned")
    void testUnconfiguredWidthIsKept() {
        when(candleRepository.deleteByBucketSecondsAndBucketStartBefore(anyInt(), any())).thenReturn(0);

        retentionWith("60:2").prune();

        verify(candleRepository).deleteByBucketSecondsAndBucketStartBefore(eq(60), any());
        verify(candleRepository, never()).deleteByBucketSecondsAndBucketStartBefore(eq(3600), any());
        verify(candleRepository, never()).deleteByBucketSecondsAndBucketStartBefore(eq(86400), any());
    }

    @Test
    @DisplayName("a malformed retention tier is rejected at construction")
    void testRejectsMalformedTiers() {
        assertThrows(IllegalArgumentException.class, () -> retentionWith("3600"));
        assertThrows(IllegalArgumentException.class, () -> retentionWith("3600:0"));
        assertThrows(IllegalArgumentException.class, () -> retentionWith("abc:2"));
        assertThrows(IllegalArgumentException.class, () -> retentionWith("-60:2"));
    }

    @Test
    @DisplayName("tiers are parsed in order, preserving width and window")
    void testTiersParseInOrder() {
        List<CandleTier> tiers = CandleTier.parse(List.of("86400:365", " 3600 : 90 ", "60:2"));

        assertEquals(3, tiers.size());
        assertEquals(new CandleTier(86400, 365), tiers.get(0));
        assertEquals(new CandleTier(3600, 90), tiers.get(1), "surrounding whitespace should be tolerated");
        assertEquals(new CandleTier(60, 2), tiers.get(2));
    }
}
