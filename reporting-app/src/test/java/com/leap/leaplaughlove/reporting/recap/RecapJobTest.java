package com.leap.leaplaughlove.reporting.recap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RecapJob")
class RecapJobTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);

    private RecapGenerator generator;
    private RecapSnapshotRepository snapshots;

    @BeforeEach
    void setUp() {
        generator = mock(RecapGenerator.class);
        snapshots = mock(RecapSnapshotRepository.class);
    }

    private RecapJob jobAt(String now) {
        return new RecapJob(generator, snapshots, Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("catch-up builds last month when its recaps are missing or out of date")
    void catchUpBuildsAnOutOfDateMonth() {
        when(snapshots.isUpToDate(SEP)).thenReturn(false);

        jobAt("2026-10-19T18:49:00Z").catchUp();

        verify(generator).generate(SEP);
    }

    @Test
    @DisplayName("catch-up leaves last month alone once it is built and up to date")
    void catchUpSkipsAnUpToDateMonth() {
        when(snapshots.isUpToDate(SEP)).thenReturn(true);

        jobAt("2026-10-19T18:49:00Z").catchUp();

        verify(generator, never()).generate(any());
    }

    @Test
    @DisplayName("a failed build is logged, not thrown, so the next catch-up run tries again")
    void failedBuildIsNotThrown() {
        when(snapshots.isUpToDate(SEP)).thenReturn(false);
        when(generator.generate(SEP)).thenThrow(new RecapPricesUnavailableException("down", null));
        RecapJob job = jobAt("2026-10-19T18:49:00Z");

        assertDoesNotThrow(job::catchUp);
        assertDoesNotThrow(job::buildLastMonth);
    }

    @Test
    @DisplayName("the monthly run builds the UTC month that just ended, across a year end")
    void monthlyRunBuildsLastMonth() {
        jobAt("2026-10-01T00:30:00Z").buildLastMonth();
        verify(generator).generate(SEP);

        jobAt("2027-01-01T00:30:00Z").buildLastMonth();
        verify(generator).generate(YearMonth.of(2026, 12));
    }
}
