package com.leap.leaplaughlove.reporting.analyst;

import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.Activation;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.BandCount;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.ClientActivity;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.CohortActivity;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.Concentration;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.MonthlyCount;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.MonthlyTotals;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.OrderSizeStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AnalystReportService")
class AnalystReportServiceTest {

    // 12 October 2026: the window is October 2025 to September 2026.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T15:00:00Z"), ZoneOffset.UTC);
    private static final ReportWindow WINDOW = new ReportWindow(YearMonth.of(2025, 10), YearMonth.of(2026, 9));
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth OCT_2025 = YearMonth.of(2025, 10);

    private AnalystReportRepository repository;
    private AnalystReportService service;

    @BeforeEach
    void setUp() {
        repository = mock(AnalystReportRepository.class);
        service = new AnalystReportService(repository, CLOCK);
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("scorecard")
    class Scorecard {

        @Test
        @DisplayName("reads a year before the window too, for year-over-year change")
        void readsAYearOfHistory() {
            service.getScorecard();

            ReportWindow withHistory = new ReportWindow(YearMonth.of(2024, 10), SEP);
            verify(repository).findMonthlyTotals(withHistory);
            verify(repository).findRegistrations(withHistory);
        }

        @Test
        @DisplayName("reports each figure with its change on last month and last year")
        void figuresAndChanges() {
            when(repository.findMonthlyTotals(WINDOW.startingEarlier(12))).thenReturn(List.of(
                    new MonthlyTotals(SEP, 30, 8, dec("1200.000000")),
                    new MonthlyTotals(AUG, 20, 4, dec("800.000000")),
                    new MonthlyTotals(SEP.minusYears(1), 10, 2, dec("400.000000"))));
            when(repository.findRegistrations(WINDOW.startingEarlier(12))).thenReturn(List.of(
                    new MonthlyCount(SEP, 6), new MonthlyCount(AUG, 4)));

            ScorecardReport report = service.getScorecard();

            assertEquals(WINDOW, report.window());
            assertEquals(12, report.months().size());
            ScorecardReport.Month sep = report.months().get(11);
            assertEquals(SEP, sep.month());
            assertEquals(new ScorecardReport.Metric(dec("1200.00"), dec("50.0"), dec("200.0")), sep.volume());
            assertEquals(new ScorecardReport.Metric(dec("8"), dec("100.0"), dec("300.0")), sep.filledOrders());
            // 150.00 a trade, against 200.00 in August and in September last year.
            assertEquals(new ScorecardReport.Metric(dec("150.00"), dec("-25.0"), dec("-25.0")), sep.averageTradeValue());
            assertEquals(new ScorecardReport.Metric(dec("30"), dec("50.0"), dec("200.0")), sep.activeClients());
            // No registrations a year ago: nothing to compare with.
            assertEquals(new ScorecardReport.Metric(dec("6"), dec("50.0"), null), sep.registrations());
        }

        @Test
        @DisplayName("shows a quiet month as zero, with no average and no change to compare against")
        void quietMonth() {
            ScorecardReport.Month first = service.getScorecard().months().get(0);

            assertEquals(OCT_2025, first.month());
            assertEquals(new ScorecardReport.Metric(dec("0.00"), null, null), first.volume());
            assertEquals(new ScorecardReport.Metric(null, null, null), first.averageTradeValue());
        }
    }

    @Test
    @DisplayName("client activity: active is new plus returning plus reactivated; lapsed is separate")
    void clientActivity() {
        when(repository.findClientActivity(WINDOW)).thenReturn(List.of(new ClientActivity(SEP, 3, 10, 2)));
        when(repository.findLapsedClients(WINDOW)).thenReturn(List.of(new MonthlyCount(SEP, 4), new MonthlyCount(AUG, 1)));

        ClientActivityReport report = service.getClientActivity();

        assertEquals(12, report.months().size());
        assertEquals(new ClientActivityReport.Month(SEP, 15, 3, 10, 2, 4), report.months().get(11));
        assertEquals(new ClientActivityReport.Month(AUG, 0, 0, 0, 0, 1), report.months().get(10));
    }

    @Test
    @DisplayName("retention: one cohort per month, each running from its sign-up month to the window's end")
    void retention() {
        when(repository.findRegistrations(WINDOW)).thenReturn(List.of(new MonthlyCount(OCT_2025, 8), new MonthlyCount(SEP, 3)));
        when(repository.findCohortActivity(WINDOW)).thenReturn(List.of(
                new CohortActivity(OCT_2025, OCT_2025, 6),
                new CohortActivity(OCT_2025, YearMonth.of(2025, 12), 2),
                new CohortActivity(SEP, SEP, 3)));

        RetentionReport report = service.getRetention();

        assertEquals(12, report.cohorts().size());
        RetentionReport.Cohort october = report.cohorts().get(0);
        assertEquals(8, october.clients());
        assertEquals(12, october.months().size());
        assertEquals(new RetentionReport.Cell(0, OCT_2025, 6, dec("75.0")), october.months().get(0));
        assertEquals(new RetentionReport.Cell(1, YearMonth.of(2025, 11), 0, dec("0.0")), october.months().get(1));
        assertEquals(new RetentionReport.Cell(2, YearMonth.of(2025, 12), 2, dec("25.0")), october.months().get(2));
        assertEquals(List.of(new RetentionReport.Cell(0, SEP, 3, dec("100.0"))), report.cohorts().get(11).months());
        // A month nobody signed up in has no percentages.
        assertNull(report.cohorts().get(1).months().get(0).activePct());
    }

    @Test
    @DisplayName("activation: percentages of the cohort, and cohorts still in their 30 days marked incomplete")
    void activation() {
        when(repository.findActivation(WINDOW)).thenReturn(List.of(
                new Activation(AUG, 4, 3, 4, dec("2.25")),
                new Activation(SEP, 2, 1, 1, dec("0.5"))));

        ActivationReport report = service.getActivation();

        // August's last sign-up had its 30 days by 1 October, before today.
        assertEquals(new ActivationReport.Cohort(AUG, 4, 3, dec("75.0"), 4, dec("100.0"), dec("2.3"), true),
                report.cohorts().get(10));
        // September's last sign-up has until 31 October.
        assertEquals(new ActivationReport.Cohort(SEP, 2, 1, dec("50.0"), 1, dec("50.0"), dec("0.5"), false),
                report.cohorts().get(11));
        ActivationReport.Cohort empty = report.cohorts().get(0);
        assertEquals(0, empty.registered());
        assertNull(empty.orderedWithin30DaysPct());
        assertNull(empty.medianDaysToFirstOrder());
        assertTrue(empty.complete());
    }

    @Test
    @DisplayName("activation: a cohort becomes complete exactly 30 days after its month ends")
    void activationSettlesAtThirtyDays() {
        Clock justBefore = Clock.fixed(Instant.parse("2026-10-30T23:59:59Z"), ZoneOffset.UTC);
        Clock exactly = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
        when(repository.findActivation(WINDOW)).thenReturn(List.of());

        assertFalse(new AnalystReportService(repository, justBefore).getActivation().cohorts().get(11).complete());
        assertTrue(new AnalystReportService(repository, exactly).getActivation().cohorts().get(11).complete());
    }

    @Test
    @DisplayName("concentration: each tier's client count rounds up, and its share is of the month's volume")
    void concentration() {
        when(repository.findConcentration(WINDOW)).thenReturn(List.of(
                new Concentration(SEP, 15, dec("1000.000000"), dec("400.000000"), dec("550.000000"), dec("700.000000"))));

        ConcentrationReport report = service.getConcentration();

        ConcentrationReport.Month sep = report.months().get(11);
        assertEquals(15, sep.tradingClients());
        assertEquals(dec("1000.00"), sep.volume());
        assertEquals(List.of(
                new ConcentrationReport.Tier(1, 1, dec("400.00"), dec("40.0")),
                new ConcentrationReport.Tier(10, 2, dec("550.00"), dec("55.0")),
                new ConcentrationReport.Tier(20, 3, dec("700.00"), dec("70.0"))), sep.tiers());
        assertEquals(new ConcentrationReport.Month(AUG, 0, dec("0.00"), List.of()), report.months().get(10));
    }

    @Test
    @DisplayName("order sizes: every band in order, empty ones included, with mean and median")
    void orderSizes() {
        when(repository.findOrderSizeStats(WINDOW)).thenReturn(List.of(
                new OrderSizeStats(SEP, 3, dec("6666.6666666"), dec("5000"))));
        when(repository.findOrderSizeBands(WINDOW)).thenReturn(List.of(
                new BandCount(SEP, OrderSizeBand.UNDER_1K, 1, dec("500.000000")),
                new BandCount(SEP, OrderSizeBand.FROM_5K, 1, dec("5000.000000")),
                new BandCount(SEP, OrderSizeBand.FROM_10K, 1, dec("14500.000000"))));

        OrderSizeReport report = service.getOrderSizes();

        OrderSizeReport.Month sep = report.months().get(11);
        assertEquals(3, sep.filledOrders());
        assertEquals(dec("6666.67"), sep.meanValue());
        assertEquals(dec("5000.00"), sep.medianValue());
        assertEquals(List.of(
                new OrderSizeReport.Band(OrderSizeBand.UNDER_1K, null, dec("1000"), 1, dec("500.00")),
                new OrderSizeReport.Band(OrderSizeBand.FROM_1K, dec("1000"), dec("5000"), 0, dec("0.00")),
                new OrderSizeReport.Band(OrderSizeBand.FROM_5K, dec("5000"), dec("10000"), 1, dec("5000.00")),
                new OrderSizeReport.Band(OrderSizeBand.FROM_10K, dec("10000"), dec("25000"), 1, dec("14500.00")),
                new OrderSizeReport.Band(OrderSizeBand.FROM_25K, dec("25000"), null, 0, dec("0.00"))), sep.bands());
        OrderSizeReport.Month quiet = report.months().get(0);
        assertEquals(0, quiet.filledOrders());
        assertNull(quiet.meanValue());
        assertNull(quiet.medianValue());
    }

    @Test
    @DisplayName("percentage change is null when there is nothing to compare against")
    void change() {
        assertEquals(dec("-50.0"), AnalystReportService.change(dec("5"), dec("10")));
        assertEquals(dec("33.3"), AnalystReportService.change(dec("4"), dec("3")));
        assertNull(AnalystReportService.change(dec("5"), BigDecimal.ZERO));
        assertNull(AnalystReportService.change(dec("5"), null));
        assertNull(AnalystReportService.change(null, dec("5")));
    }
}
