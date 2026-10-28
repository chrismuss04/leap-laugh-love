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
import java.util.List;
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
        return getOverview(period, 0, 10, "", "", "name", "ascending");
    }

    public InstrumentOverviewResponse getOverview(String period, int page, int size, String query,
            String asset, String sort, String direction) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid page or size");
        }
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
        boolean sortByLiveValue = sort != null && !"name".equals(sort);
        var candidatePage = repository.findPage(start, today ? 300 : 86400,
                sortByLiveValue ? 0 : page, sortByLiveValue ? Integer.MAX_VALUE : size,
                query, asset, sort, direction);
        List<InstrumentOverviewRepository.Metadata> currentRows = candidatePage.rows();
        long total = candidatePage.total();
        var moverRows = java.util.stream.Stream.concat(
                repository.findMovers(start, today ? 300 : 86400, true, 100).stream(),
                repository.findMovers(start, today ? 300 : 86400, false, 100).stream())
                .collect(Collectors.toMap(InstrumentOverviewRepository.Metadata::symbol, Function.identity(),
                        (first, second) -> first)).values();
        var instruments = currentRows.stream()
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
                }).sorted(instrumentComparator(sort, direction)).toList();
        if (sortByLiveValue) {
            int from = Math.min(instruments.size(), page * size);
            instruments = instruments.subList(from, Math.min(instruments.size(), from + size));
        }
        var allForMovers = moverRows.stream().map(metadata -> {
            PriceState price = prices.get(metadata.symbol());
            if (price == null || metadata.baselinePrice() == null || metadata.baselinePrice().signum() <= 0) return null;
            BigDecimal change = price.price().subtract(metadata.baselinePrice());
            BigDecimal percent = change.multiply(BigDecimal.valueOf(100))
                    .divide(metadata.baselinePrice(), 12, RoundingMode.HALF_UP);
            return new Instrument(metadata.symbol(), metadata.name(), metadata.assetClass(), metadata.currency(),
                    price.price(), price.asOf(), metadata.baselinePrice(), change, percent);
        }).filter(java.util.Objects::nonNull).toList();
        List<Instrument> gainers = allForMovers.stream().filter(row -> row.changePercent().signum() > 0)
                .sorted((a, b) -> b.changePercent().compareTo(a.changePercent())).limit(5).toList();
        List<Instrument> losers = allForMovers.stream().filter(row -> row.changePercent().signum() < 0)
                .sorted((a, b) -> a.changePercent().compareTo(b.changePercent())).limit(5).toList();
        return new InstrumentOverviewResponse(period, start, now, instruments, total, page, size,
                gainers, losers);
    }

    private java.util.Comparator<Instrument> instrumentComparator(String sort, String direction) {
        java.util.Comparator<Instrument> comparator = switch (sort == null ? "name" : sort) {
            case "price" -> java.util.Comparator.comparing(Instrument::price);
            case "change" -> java.util.Comparator.comparing(Instrument::change,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
            case "changePercent" -> java.util.Comparator.comparing(Instrument::changePercent,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
            default -> java.util.Comparator.comparing(Instrument::name, String.CASE_INSENSITIVE_ORDER);
        };
        if ("descending".equalsIgnoreCase(direction)) comparator = comparator.reversed();
        return comparator.thenComparing(Instrument::symbol);
    }

}
