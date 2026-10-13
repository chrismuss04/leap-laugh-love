package com.leap.leaplaughlove.reporting.analyst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ReportWindow")
class ReportWindowTest {

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @ParameterizedTest(name = "{0} -> {1} to {2}")
    @CsvSource({
            "2026-10-12T15:00:00Z, 2025-10, 2026-09",
            // The first instant of a month already leaves the previous one complete.
            "2026-10-01T00:00:00Z, 2025-10, 2026-09",
            "2026-09-30T23:59:59Z, 2025-09, 2026-08",
            // Across a year end.
            "2027-01-05T08:00:00Z, 2026-01, 2026-12"
    })
    @DisplayName("covers the last twelve complete UTC months")
    void trailingMonths(String now, String first, String last) {
        ReportWindow window = ReportWindow.trailingMonths(at(now));

        assertEquals(YearMonth.parse(first), window.firstMonth());
        assertEquals(YearMonth.parse(last), window.lastMonth());
        assertEquals(12, window.months().size());
    }

    @Test
    @DisplayName("measures months in UTC whatever the clock's zone")
    void usesUtc() {
        // 1 October in Tokyo, still 30 September in UTC.
        Clock tokyo = Clock.fixed(Instant.parse("2026-09-30T20:00:00Z"), ZoneId.of("Asia/Tokyo"));

        assertEquals(YearMonth.of(2026, 8), ReportWindow.trailingMonths(tokyo).lastMonth());
    }

    @Test
    @DisplayName("gives half-open bounds as dates and UTC instants")
    void bounds() {
        ReportWindow window = new ReportWindow(YearMonth.of(2025, 10), YearMonth.of(2026, 9));

        assertEquals(LocalDate.of(2025, 10, 1), window.startDate());
        assertEquals(LocalDate.of(2026, 10, 1), window.endDate());
        assertEquals(OffsetDateTime.parse("2025-10-01T00:00:00Z"), window.startInstant());
        assertEquals(OffsetDateTime.parse("2026-10-01T00:00:00Z"), window.endInstant());
    }

    @Test
    @DisplayName("lists its months oldest first and can start earlier")
    void months() {
        ReportWindow window = new ReportWindow(YearMonth.of(2025, 11), YearMonth.of(2026, 1));

        assertEquals(java.util.List.of(YearMonth.of(2025, 11), YearMonth.of(2025, 12), YearMonth.of(2026, 1)), window.months());
        assertEquals(new ReportWindow(YearMonth.of(2024, 11), YearMonth.of(2026, 1)), window.startingEarlier(12));
    }
}
