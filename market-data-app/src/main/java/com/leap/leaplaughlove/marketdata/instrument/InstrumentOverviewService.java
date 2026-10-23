package com.leap.leaplaughlove.marketdata.instrument;

import com.leap.leaplaughlove.common.market.MarketCalendar;
import com.leap.leaplaughlove.marketdata.api.InstrumentOverviewResponse;
import com.leap.leaplaughlove.marketdata.api.InstrumentOverviewResponse.Instrument;
import com.leap.leaplaughlove.marketdata.simulation.MarketSimulationEngine;
import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Combines latest simulation prices with shared UTC period baselines. */
@Service
public class InstrumentOverviewService {
    private final InstrumentOverviewRepository repository;
    private final MarketSimulationEngine simulation;

    /**
     * Constructs a new InstrumentOverviewService.
     * @param repository loads instrument metadata and period baselines
     * @param simulation supplies the latest prices
     */
    public InstrumentOverviewService(InstrumentOverviewRepository repository, MarketSimulationEngine simulation) {
        this.repository = repository;
        this.simulation = simulation;
    }

    /**
     * Builds the overview for a period. An instrument with no usable baseline is still listed,
     * with its change left null.
     * @param period {@code today}, {@code week} or {@code month}. Today starts at the previous
     *     trading day's regular close, like every other day change in the app (see
     *     {@link MarketCalendar#previousClose}); a week or month starts at UTC midnight that many
     *     days ago
     * @return the latest prices with their changes since the period start
     * @throws ResponseStatusException 400 if the period isn't one of the three
     */
    public InstrumentOverviewResponse getOverview(String period) {
        int days = switch (period) {
            case "today" -> 0;
            case "week" -> 7;
            case "month" -> 30;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "period must be today, week, or month");
        };
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean today = days == 0;
        OffsetDateTime start = today
                ? MarketCalendar.previousClose(now.toInstant()).atOffset(ZoneOffset.UTC)
                : now.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC).minusDays(days);
        Map<String, PriceState> prices = simulation.latestAll().stream()
                .collect(Collectors.toMap(PriceState::symbol, Function.identity()));
        // Today's baseline is the 5-minute candle that ended on the close; a longer period's is a day's.
        var instruments = repository.findAll(start, today ? 300 : 86400).stream()
                .filter(metadata -> prices.containsKey(metadata.symbol()))
                .map(metadata -> {
                    PriceState price = prices.get(metadata.symbol());
                    BigDecimal baseline = metadata.baselinePrice();
                    boolean available = baseline != null && baseline.signum() > 0;
                    BigDecimal change = available ? price.price().subtract(baseline) : null;
                    BigDecimal percent = available
                            ? change.multiply(BigDecimal.valueOf(100)).divide(baseline, 12, RoundingMode.HALF_UP)
                            : null;
                    return new Instrument(metadata.symbol(), metadata.name(), metadata.assetClass(),
                            metadata.currency(), price.price(), price.asOf(), available ? baseline : null,
                            change, percent);
                }).toList();
        return new InstrumentOverviewResponse(period, start, now, instruments);
    }
}
