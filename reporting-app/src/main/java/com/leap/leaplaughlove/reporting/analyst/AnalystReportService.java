package com.leap.leaplaughlove.reporting.analyst;

import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.Activation;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.BandCount;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.ClientActivity;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.CohortActivity;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.Concentration;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.MonthlyCount;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.MonthlyTotals;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.OrderSizeStats;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the commercial analysts' long-range reports over the last twelve complete UTC months
 * (see {@link ReportWindow#trailingMonths}). The queries only return months that had activity;
 * this fills in the rest with zeros, so every report has one entry for every month in the window.
 */
@Service
public class AnalystReportService {

    /** The slices of a month's trading clients the concentration report shows, in percent. */
    static final int[] CONCENTRATION_TIERS = {1, 10, 20};

    /** How long after signing up a first order still counts towards activation. */
    static final int ACTIVATION_DAYS = 30;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AnalystReportRepository repository;
    private final Clock clock;

    /**
     * Constructs an instance of AnalystReportService.
     * @param repository the repository the reports' figures are read from
     * @param clock the clock the reporting window is measured from
     */
    public AnalystReportService(AnalystReportRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Builds the scorecard: volume, filled orders, average trade value, active clients and
     * registrations per month, each with month-over-month and year-over-year change.
     * @return the scorecard for the current window
     */
    public ScorecardReport getScorecard() {
        ReportWindow window = ReportWindow.trailingMonths(clock);
        // A year earlier as well, so the window's first month has a year-ago month to compare with.
        ReportWindow withHistory = window.startingEarlier(12);
        Map<YearMonth, MonthlyTotals> totals = byMonth(repository.findMonthlyTotals(withHistory), MonthlyTotals::month);
        Map<YearMonth, MonthlyCount> registrations = byMonth(repository.findRegistrations(withHistory), MonthlyCount::month);

        Function<YearMonth, BigDecimal> volume = month -> money(totals.containsKey(month) ? totals.get(month).volume() : BigDecimal.ZERO);
        Function<YearMonth, BigDecimal> filledOrders = month -> BigDecimal.valueOf(totals.containsKey(month) ? totals.get(month).filledOrders() : 0);
        Function<YearMonth, BigDecimal> averageTradeValue = month -> filledOrders.apply(month).signum() == 0 ? null
                : volume.apply(month).divide(filledOrders.apply(month), 2, RoundingMode.HALF_UP);
        Function<YearMonth, BigDecimal> activeClients = month -> BigDecimal.valueOf(totals.containsKey(month) ? totals.get(month).activeClients() : 0);
        Function<YearMonth, BigDecimal> signUps = month -> BigDecimal.valueOf(registrations.containsKey(month) ? registrations.get(month).clients() : 0);

        List<ScorecardReport.Month> months = window.months().stream()
                .map(month -> new ScorecardReport.Month(month,
                        metric(volume, month),
                        metric(filledOrders, month),
                        metric(averageTradeValue, month),
                        metric(activeClients, month),
                        metric(signUps, month)))
                .toList();
        return new ScorecardReport(window, months);
    }

    /**
     * Builds the client activity report: each month's active clients split into new, returning
     * and reactivated, with the clients who lapsed.
     * @return the client activity report for the current window
     */
    public ClientActivityReport getClientActivity() {
        ReportWindow window = ReportWindow.trailingMonths(clock);
        Map<YearMonth, ClientActivity> activity = byMonth(repository.findClientActivity(window), ClientActivity::month);
        Map<YearMonth, MonthlyCount> lapsed = byMonth(repository.findLapsedClients(window), MonthlyCount::month);

        List<ClientActivityReport.Month> months = window.months().stream()
                .map(month -> {
                    ClientActivity counts = activity.getOrDefault(month, new ClientActivity(month, 0, 0, 0));
                    long lapsedClients = lapsed.containsKey(month) ? lapsed.get(month).clients() : 0;
                    return new ClientActivityReport.Month(month,
                            counts.newClients() + counts.returningClients() + counts.reactivatedClients(),
                            counts.newClients(), counts.returningClients(), counts.reactivatedClients(), lapsedClients);
                })
                .toList();
        return new ClientActivityReport(window, months);
    }

    /**
     * Builds the retention matrix: for each sign-up month in the window, the share of its clients
     * active in each month from then to the window's end.
     * @return the retention report for the current window
     */
    public RetentionReport getRetention() {
        ReportWindow window = ReportWindow.trailingMonths(clock);
        Map<YearMonth, MonthlyCount> sizes = byMonth(repository.findRegistrations(window), MonthlyCount::month);
        Map<YearMonth, Map<YearMonth, Long>> active = repository.findCohortActivity(window).stream()
                .collect(Collectors.groupingBy(CohortActivity::cohort,
                        Collectors.toMap(CohortActivity::month, CohortActivity::activeClients)));

        List<RetentionReport.Cohort> cohorts = window.months().stream()
                .map(cohort -> {
                    long clients = sizes.containsKey(cohort) ? sizes.get(cohort).clients() : 0;
                    Map<YearMonth, Long> cohortActive = active.getOrDefault(cohort, Map.of());
                    List<RetentionReport.Cell> cells = new ArrayList<>();
                    for (YearMonth month = cohort; !month.isAfter(window.lastMonth()); month = month.plusMonths(1)) {
                        long activeClients = cohortActive.getOrDefault(month, 0L);
                        cells.add(new RetentionReport.Cell(cells.size(), month, activeClients, percent(activeClients, clients)));
                    }
                    return new RetentionReport.Cohort(cohort, clients, cells);
                })
                .toList();
        return new RetentionReport(window, cohorts);
    }

    /**
     * Builds the activation report: for each sign-up month in the window, how many of its clients
     * ordered within {@value #ACTIVATION_DAYS} days, how many have ordered at all, and how soon.
     * @return the activation report for the current window
     */
    public ActivationReport getActivation() {
        ReportWindow window = ReportWindow.trailingMonths(clock);
        Map<YearMonth, Activation> activation = byMonth(repository.findActivation(window), Activation::cohort);
        Instant now = clock.instant();

        List<ActivationReport.Cohort> cohorts = window.months().stream()
                .map(cohort -> {
                    Activation counts = activation.getOrDefault(cohort, new Activation(cohort, 0, 0, 0, null));
                    // The cohort's last client signed up just before the month ended, and has the
                    // full 30 days from then.
                    Instant settled = cohort.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC)
                            .plusDays(ACTIVATION_DAYS).toInstant();
                    BigDecimal medianDays = counts.medianDaysToFirstOrder() == null ? null
                            : counts.medianDaysToFirstOrder().setScale(1, RoundingMode.HALF_UP);
                    return new ActivationReport.Cohort(cohort, counts.registered(),
                            counts.orderedWithin30Days(), percent(counts.orderedWithin30Days(), counts.registered()),
                            counts.orderedEver(), percent(counts.orderedEver(), counts.registered()),
                            medianDays, !now.isBefore(settled));
                })
                .toList();
        return new ActivationReport(window, cohorts);
    }

    /**
     * Builds the concentration report: each month's traded volume and the share of it from the
     * top 1, 10 and 20 percent of trading clients.
     * @return the concentration report for the current window
     */
    public ConcentrationReport getConcentration() {
        ReportWindow window = ReportWindow.trailingMonths(clock);
        Map<YearMonth, Concentration> concentration = byMonth(repository.findConcentration(window), Concentration::month);

        List<ConcentrationReport.Month> months = window.months().stream()
                .map(month -> {
                    Concentration row = concentration.get(month);
                    if (row == null) {
                        return new ConcentrationReport.Month(month, 0, money(BigDecimal.ZERO), List.of());
                    }
                    List<BigDecimal> topVolumes = List.of(row.top1PctVolume(), row.top10PctVolume(), row.top20PctVolume());
                    List<ConcentrationReport.Tier> tiers = new ArrayList<>();
                    for (int i = 0; i < CONCENTRATION_TIERS.length; i++) {
                        int topPct = CONCENTRATION_TIERS[i];
                        BigDecimal volume = money(topVolumes.get(i));
                        tiers.add(new ConcentrationReport.Tier(topPct, Math.ceilDiv(row.traders() * topPct, 100),
                                volume, percent(topVolumes.get(i), row.volume())));
                    }
                    return new ConcentrationReport.Month(month, row.traders(), money(row.volume()), tiers);
                })
                .toList();
        return new ConcentrationReport(window, months);
    }

    /**
     * Builds the order size report: each month's filled orders by value band, with the mean and
     * median filled order value.
     * @return the order size report for the current window
     */
    public OrderSizeReport getOrderSizes() {
        ReportWindow window = ReportWindow.trailingMonths(clock);
        Map<YearMonth, OrderSizeStats> stats = byMonth(repository.findOrderSizeStats(window), OrderSizeStats::month);
        Map<YearMonth, Map<OrderSizeBand, BandCount>> bands = repository.findOrderSizeBands(window).stream()
                .collect(Collectors.groupingBy(BandCount::month, Collectors.toMap(BandCount::band, Function.identity())));

        List<OrderSizeReport.Month> months = window.months().stream()
                .map(month -> {
                    OrderSizeStats monthStats = stats.getOrDefault(month, new OrderSizeStats(month, 0, null, null));
                    Map<OrderSizeBand, BandCount> monthBands = bands.getOrDefault(month, Map.of());
                    List<OrderSizeReport.Band> bandRows = Arrays.stream(OrderSizeBand.values())
                            .map(band -> {
                                BandCount count = monthBands.get(band);
                                return new OrderSizeReport.Band(band, band.from(), band.to(),
                                        count == null ? 0 : count.orders(),
                                        money(count == null ? BigDecimal.ZERO : count.value()));
                            })
                            .toList();
                    return new OrderSizeReport.Month(month, monthStats.orders(),
                            money(monthStats.mean()), money(monthStats.median()), bandRows);
                })
                .toList();
        return new OrderSizeReport(window, months);
    }

    private static ScorecardReport.Metric metric(Function<YearMonth, BigDecimal> figure, YearMonth month) {
        BigDecimal value = figure.apply(month);
        return new ScorecardReport.Metric(value,
                change(value, figure.apply(month.minusMonths(1))),
                change(value, figure.apply(month.minusMonths(12))));
    }

    /** Percentage change from earlier to now, or null when earlier is zero or missing. */
    static BigDecimal change(BigDecimal now, BigDecimal earlier) {
        if (now == null || earlier == null || earlier.signum() == 0) {
            return null;
        }
        return now.subtract(earlier).multiply(HUNDRED).divide(earlier, 1, RoundingMode.HALF_UP);
    }

    /** part as a percentage of whole, or null when whole is zero. */
    static BigDecimal percent(long part, long whole) {
        return percent(BigDecimal.valueOf(part), BigDecimal.valueOf(whole));
    }

    /** part as a percentage of whole, or null when whole is zero or missing. */
    static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static <T> Map<YearMonth, T> byMonth(List<T> rows, Function<T, YearMonth> month) {
        return rows.stream().collect(Collectors.toMap(month, Function.identity()));
    }
}
