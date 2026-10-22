package com.leap.leaplaughlove.marketdata.instrument;

import com.leap.leaplaughlove.marketdata.simulation.MarketSimulationEngine;
import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.leap.leaplaughlove.common.market.MarketCalendar;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

class InstrumentOverviewServiceTest {
    private final InstrumentOverviewRepository repository = mock(InstrumentOverviewRepository.class);
    private final MarketSimulationEngine simulation = mock(MarketSimulationEngine.class);
    private final InstrumentOverviewService service = new InstrumentOverviewService(repository, simulation);

    @ParameterizedTest
    @CsvSource({"week,7", "month,30"})
    void longerPeriodsUseUtcMidnight(String period, int days) {
        when(simulation.latestAll()).thenReturn(List.of());
        when(repository.findAll(any(), anyInt())).thenReturn(List.of());
        var response = service.getOverview(period);
        assertThat(response.period()).isEqualTo(period);
        assertThat(response.periodStart()).isEqualTo(response.asOf().toLocalDate()
                .atStartOfDay().atOffset(ZoneOffset.UTC).minusDays(days));
        verify(repository).findAll(response.periodStart(), 86400);
    }

    @Test
    void todayStartsAtThePreviousCloseLikeTheDashboard() {
        when(simulation.latestAll()).thenReturn(List.of());
        when(repository.findAll(any(), anyInt())).thenReturn(List.of());
        var response = service.getOverview("today");
        assertThat(response.periodStart().toInstant())
                .isEqualTo(MarketCalendar.previousClose(response.asOf().toInstant()));
        verify(repository).findAll(response.periodStart(), 300);
    }

    @ParameterizedTest
    @CsvSource({"110,100,10,10", "90,100,-10,-10", "100,100,0,0", "1,3,-2,-66.666666666667"})
    void calculatesSignedPerformance(String price, String baseline, String change, String percent) {
        prepare(new BigDecimal(price), new BigDecimal(baseline));
        var row = service.getOverview("today").instruments().getFirst();
        assertThat(row.change()).isEqualByComparingTo(change);
        assertThat(row.changePercent()).isEqualByComparingTo(percent);
        assertThat(row.currency()).isEqualTo("USD");
    }

    @Test
    void unavailableBaselinesDoNotFabricatePerformance() {
        for (BigDecimal baseline : new BigDecimal[] {null, BigDecimal.ZERO, BigDecimal.ONE.negate()}) {
            prepare(BigDecimal.TEN, baseline);
            var row = service.getOverview("today").instruments().getFirst();
            assertThat(row.baselinePrice()).isNull();
            assertThat(row.change()).isNull();
            assertThat(row.changePercent()).isNull();
        }
    }

    @Test
    void preservesUnknownMetadataAndOmitsMissingSnapshots() {
        when(repository.findAll(any(), anyInt())).thenReturn(List.of(
                new InstrumentOverviewRepository.Metadata("INDEX", "Index", null, null, null),
                new InstrumentOverviewRepository.Metadata("ABSENT", "Absent", "EQUITY", "USD", null)));
        when(simulation.latestAll()).thenReturn(List.of(
                new PriceState("INDEX", BigDecimal.TEN, OffsetDateTime.now(ZoneOffset.UTC))));
        var rows = service.getOverview("today").instruments();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().symbol()).isEqualTo("INDEX");
        assertThat(rows.getFirst().assetClass()).isNull();
        assertThat(rows.getFirst().currency()).isNull();
    }

    @Test
    void rejectsUnsupportedPeriodBeforeReadingData() {
        assertThatThrownBy(() -> service.getOverview("year"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(repository, simulation);
    }

    private void prepare(BigDecimal price, BigDecimal baseline) {
        when(repository.findAll(any(), anyInt())).thenReturn(List.of(
                new InstrumentOverviewRepository.Metadata("AAPL", "Apple", "EQUITY", "USD", baseline)));
        when(simulation.latestAll()).thenReturn(List.of(
                new PriceState("AAPL", price, OffsetDateTime.now(ZoneOffset.UTC))));
    }
}
