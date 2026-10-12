package com.leap.leaplaughlove.order.report;

import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.instrument.InstrumentRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Builds commercial analysts' activity reports: trade counts, buy/sell totals and volume over a
 * period, broken down by UTC day or ISO week. Only FILLED orders count.
 */
@Service
public class ActivityReportService {

    /** The longest period one report may cover, in days. */
    static final int MAX_PERIOD_DAYS = 366;

    private final ActivityReportRepository activityReportRepository;
    private final InstrumentRepository instrumentRepository;

    /**
     * Constructs an instance of ActivityReportService.
     * @param activityReportRepository the repository the fills are read from
     * @param instrumentRepository the repository used to resolve the instrument filter
     */
    public ActivityReportService(ActivityReportRepository activityReportRepository,
                                 InstrumentRepository instrumentRepository) {
        this.activityReportRepository = activityReportRepository;
        this.instrumentRepository = instrumentRepository;
    }

    /**
     * Builds the activity report for a period.
     * @param from the first UTC day of the period, inclusive
     * @param to the last UTC day of the period, inclusive
     * @param granularity whether to break the period down by day or by ISO week
     * @param instrumentId the instrument to report on, or null for all instruments
     * @return the report; its buckets are empty when nothing traded in the period
     * @throws IllegalArgumentException if the period or instrument is invalid
     */
    public ActivityReport getActivityReport(LocalDate from, LocalDate to,
                                            ReportGranularity granularity, UUID instrumentId) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw new IllegalArgumentException("A report can cover at most " + MAX_PERIOD_DAYS + " days");
        }
        if (granularity == null) {
            throw new IllegalArgumentException("granularity is required");
        }
        ReportInstrument instrument = instrumentId == null ? null : instrumentRepository.findById(instrumentId)
                .map(ActivityReportService::toReportInstrument)
                .orElseThrow(() -> new IllegalArgumentException("Unknown instrument: " + instrumentId));

        List<ReportedFill> fills = activityReportRepository.findFills(
                from.atStartOfDay().atOffset(ZoneOffset.UTC),
                to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC),
                instrumentId);

        Accumulator total = new Accumulator();
        Map<LocalDate, Accumulator> byPeriod = new TreeMap<>();
        for (ReportedFill fill : fills) {
            LocalDate day = fill.completedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
            total.add(fill);
            byPeriod.computeIfAbsent(periodStart(day, granularity), d -> new Accumulator()).add(fill);
        }

        List<ActivityBucket> buckets = byPeriod.entrySet().stream()
                .map(e -> new ActivityBucket(e.getKey(), e.getValue().toTotals()))
                .toList();
        return new ActivityReport(from, to, granularity, instrument, total.toTotals(), buckets);
    }

    /**
     * Lists the instruments a report can be filtered by.
     * @return every instrument, ordered by symbol
     */
    public List<ReportInstrument> getInstruments() {
        return instrumentRepository.findAll(Sort.by("symbol")).stream()
                .map(ActivityReportService::toReportInstrument)
                .toList();
    }

    // ISO weeks start on Monday, even when the report period starts mid-week.
    private static LocalDate periodStart(LocalDate day, ReportGranularity granularity) {
        return granularity == ReportGranularity.WEEKLY
                ? day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : day;
    }

    private static ReportInstrument toReportInstrument(Instrument instrument) {
        return new ReportInstrument(instrument.getInstrumentId(), instrument.getSymbol());
    }

    private static final class Accumulator {
        private long buyCount;
        private long sellCount;
        private BigDecimal buyValue = BigDecimal.ZERO;
        private BigDecimal sellValue = BigDecimal.ZERO;
        private long volume;

        void add(ReportedFill fill) {
            BigDecimal value = fill.fillPrice().multiply(BigDecimal.valueOf(fill.quantity()));
            if ("BUY".equals(fill.side())) {
                buyCount++;
                buyValue = buyValue.add(value);
            } else {
                sellCount++;
                sellValue = sellValue.add(value);
            }
            volume += fill.quantity();
        }

        ActivityTotals toTotals() {
            return new ActivityTotals(buyCount + sellCount, buyCount, sellCount, buyValue, sellValue, volume);
        }
    }
}
