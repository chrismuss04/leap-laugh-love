package com.leap.leaplaughlove.order.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BusinessInsightsService Tests")
class BusinessInsightsServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 7);
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-01T00:00:00Z");
    private static final OffsetDateTime END = OffsetDateTime.parse("2026-10-08T00:00:00Z");
    private static final OffsetDateTime PREVIOUS_START = OffsetDateTime.parse("2026-09-24T00:00:00Z");

    @Mock
    private BusinessInsightsRepository repository;

    private BusinessInsightsService service;

    @BeforeEach
    void setUp() {
        service = new BusinessInsightsService(repository);
    }

    @Test
    @DisplayName("Reads the period, the equally long period before it, and the top instruments")
    void comparesWithThePreviousPeriod() {
        InsightFigures current = new InsightFigures(new BigDecimal("1702.50"), 3, new BigDecimal("567.50"), 4, 2);
        InsightFigures previous = new InsightFigures(BigDecimal.ZERO, 0, null, 1, 0);
        List<InstrumentActivity> top = List.of(new InstrumentActivity("AAPL", 2, new BigDecimal("1600.00")));
        when(repository.findFigures(START, END)).thenReturn(current);
        when(repository.findFigures(PREVIOUS_START, START)).thenReturn(previous);
        when(repository.findTopInstruments(START, END, BusinessInsightsService.TOP_INSTRUMENTS)).thenReturn(top);

        BusinessInsights insights = service.getInsights(FROM, TO);

        assertEquals(FROM, insights.from());
        assertEquals(TO, insights.to());
        assertEquals(LocalDate.of(2026, 9, 24), insights.previousFrom());
        assertEquals(LocalDate.of(2026, 9, 30), insights.previousTo());
        assertSame(current, insights.current());
        assertSame(previous, insights.previous());
        assertEquals(top, insights.topInstruments());
    }

    @Test
    @DisplayName("A single day is compared with the day before")
    void singleDay() {
        BusinessInsights insights = service.getInsights(FROM, FROM);

        assertEquals(LocalDate.of(2026, 9, 30), insights.previousFrom());
        assertEquals(LocalDate.of(2026, 9, 30), insights.previousTo());
    }

    @Test
    @DisplayName("Rejects a missing, backwards or over-long period without querying")
    void invalidPeriod() {
        assertThrows(IllegalArgumentException.class, () -> service.getInsights(null, TO));
        assertThrows(IllegalArgumentException.class, () -> service.getInsights(TO, FROM));
        LocalDate tooEarly = TO.minusDays(ActivityReportService.MAX_PERIOD_DAYS);
        assertThrows(IllegalArgumentException.class, () -> service.getInsights(tooEarly, TO));
        verifyNoInteractions(repository);
    }
}
