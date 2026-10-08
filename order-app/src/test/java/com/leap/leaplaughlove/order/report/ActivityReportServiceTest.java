package com.leap.leaplaughlove.order.report;

import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.instrument.InstrumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityReportService Tests")
class ActivityReportServiceTest {

    // Wednesday 2026-09-30 to Tuesday 2026-10-06: spans two ISO weeks and a month boundary.
    private static final LocalDate FROM = LocalDate.of(2026, 9, 30);
    private static final LocalDate TO = LocalDate.of(2026, 10, 6);

    @Mock
    private ActivityReportRepository activityReportRepository;

    @Mock
    private InstrumentRepository instrumentRepository;

    private ActivityReportService service;
    private Instrument aapl;

    @BeforeEach
    void setUp() {
        service = new ActivityReportService(activityReportRepository, instrumentRepository);
        aapl = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
    }

    private static ReportedFill fill(String side, long quantity, String price, String completedAt) {
        return new ReportedFill(side, quantity, new BigDecimal(price), OffsetDateTime.parse(completedAt));
    }

    private void givenFills(ReportedFill... fills) {
        when(activityReportRepository.findFills(any(), any(), any())).thenReturn(List.of(fills));
    }

    @Test
    @DisplayName("Totals count buys and sells, their values and the volume traded")
    void totalsCoverTheWholePeriod() {
        givenFills(
                fill("BUY", 10, "100.00", "2026-09-30T09:00:00Z"),
                fill("BUY", 5, "20.50", "2026-10-01T12:00:00Z"),
                fill("SELL", 4, "150.00", "2026-10-05T15:30:00Z"));

        ActivityReport report = service.getActivityReport(FROM, TO, ReportGranularity.DAILY, null);

        ActivityTotals totals = report.totals();
        assertEquals(3, totals.tradeCount());
        assertEquals(2, totals.buyCount());
        assertEquals(1, totals.sellCount());
        assertEquals(0, new BigDecimal("1102.50").compareTo(totals.buyValue()));
        assertEquals(0, new BigDecimal("600.00").compareTo(totals.sellValue()));
        assertEquals(19, totals.volume());
        assertNull(report.instrument());
    }

    @Test
    @DisplayName("Daily buckets hold one UTC day each, oldest first, skipping days with no trades")
    void dailyBuckets() {
        givenFills(
                fill("SELL", 4, "150.00", "2026-10-05T15:30:00Z"),
                fill("BUY", 10, "100.00", "2026-09-30T09:00:00Z"),
                fill("BUY", 5, "20.00", "2026-09-30T23:59:59Z"));

        List<ActivityBucket> buckets =
                service.getActivityReport(FROM, TO, ReportGranularity.DAILY, null).buckets();

        assertEquals(List.of(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 5)),
                buckets.stream().map(ActivityBucket::periodStart).toList());
        assertEquals(2, buckets.get(0).totals().buyCount());
        assertEquals(15, buckets.get(0).totals().volume());
        assertEquals(1, buckets.get(1).totals().sellCount());
    }

    @Test
    @DisplayName("Days are UTC days, whatever offset the fill time carries")
    void dailyBucketsUseUtc() {
        givenFills(fill("BUY", 1, "10.00", "2026-10-02T01:00:00+02:00"));

        List<ActivityBucket> buckets =
                service.getActivityReport(FROM, TO, ReportGranularity.DAILY, null).buckets();

        assertEquals(LocalDate.of(2026, 10, 1), buckets.get(0).periodStart());
    }

    @Test
    @DisplayName("Weekly buckets start on the Monday of each ISO week, across a month boundary")
    void weeklyBuckets() {
        givenFills(
                fill("BUY", 10, "100.00", "2026-09-30T09:00:00Z"),
                fill("SELL", 2, "50.00", "2026-10-04T18:00:00Z"),
                fill("BUY", 3, "10.00", "2026-10-05T10:00:00Z"));

        List<ActivityBucket> buckets =
                service.getActivityReport(FROM, TO, ReportGranularity.WEEKLY, null).buckets();

        assertEquals(List.of(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5)),
                buckets.stream().map(ActivityBucket::periodStart).toList());
        assertEquals(2, buckets.get(0).totals().tradeCount());
        assertEquals(12, buckets.get(0).totals().volume());
        assertEquals(1, buckets.get(1).totals().tradeCount());
    }

    @Test
    @DisplayName("A period with no trades has empty buckets and zero totals")
    void emptyPeriod() {
        givenFills();

        ActivityReport report = service.getActivityReport(FROM, TO, ReportGranularity.DAILY, null);

        assertTrue(report.buckets().isEmpty());
        assertEquals(ActivityTotals.EMPTY, report.totals());
    }

    @Test
    @DisplayName("The fills are read from the start of from to the start of the day after to, in UTC")
    void queriesTheWholeLastDay() {
        givenFills();

        service.getActivityReport(FROM, TO, ReportGranularity.DAILY, null);

        verify(activityReportRepository).findFills(
                eq(OffsetDateTime.of(2026, 9, 30, 0, 0, 0, 0, ZoneOffset.UTC)),
                eq(OffsetDateTime.of(2026, 10, 7, 0, 0, 0, 0, ZoneOffset.UTC)),
                isNull());
    }

    @Test
    @DisplayName("An instrument filter is passed to the query and named in the report")
    void instrumentFilter() {
        when(instrumentRepository.findById(aapl.getInstrumentId())).thenReturn(Optional.of(aapl));
        givenFills();

        ActivityReport report = service.getActivityReport(FROM, TO, ReportGranularity.DAILY, aapl.getInstrumentId());

        assertEquals(new ReportInstrument(aapl.getInstrumentId(), "AAPL"), report.instrument());
        verify(activityReportRepository).findFills(any(), any(), eq(aapl.getInstrumentId()));
    }

    @Test
    @DisplayName("A single-day period is allowed")
    void singleDay() {
        givenFills();

        assertDoesNotThrow(() -> service.getActivityReport(FROM, FROM, ReportGranularity.DAILY, null));
    }

    @Test
    @DisplayName("An unknown instrument is rejected")
    void unknownInstrument() {
        UUID unknown = UUID.randomUUID();
        when(instrumentRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.getActivityReport(FROM, TO, ReportGranularity.DAILY, unknown));
        verifyNoInteractions(activityReportRepository);
    }

    @Test
    @DisplayName("from after to is rejected")
    void fromAfterTo() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getActivityReport(TO, FROM, ReportGranularity.DAILY, null));
        verifyNoInteractions(activityReportRepository);
    }

    @Test
    @DisplayName("A period longer than the maximum is rejected; exactly the maximum is allowed")
    void maximumPeriod() {
        LocalDate lastAllowed = FROM.plusDays(ActivityReportService.MAX_PERIOD_DAYS - 1);
        givenFills();

        assertDoesNotThrow(() -> service.getActivityReport(FROM, lastAllowed, ReportGranularity.DAILY, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.getActivityReport(FROM, lastAllowed.plusDays(1), ReportGranularity.DAILY, null));
    }

    @Test
    @DisplayName("Missing dates or granularity are rejected")
    void missingParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getActivityReport(null, TO, ReportGranularity.DAILY, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.getActivityReport(FROM, null, ReportGranularity.DAILY, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.getActivityReport(FROM, TO, null, null));
        verifyNoInteractions(activityReportRepository);
    }

    @Test
    @DisplayName("Instruments are listed by symbol")
    void instruments() {
        Instrument msft = new Instrument(UUID.randomUUID(), "MSFT", "Microsoft Corp.", "EQUITY", "NASDAQ", "USD", true);
        when(instrumentRepository.findAll(Sort.by("symbol"))).thenReturn(List.of(aapl, msft));

        assertEquals(List.of(new ReportInstrument(aapl.getInstrumentId(), "AAPL"),
                        new ReportInstrument(msft.getInstrumentId(), "MSFT")),
                service.getInstruments());
    }
}
