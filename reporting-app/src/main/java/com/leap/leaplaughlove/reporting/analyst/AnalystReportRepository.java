package com.leap.leaplaughlove.reporting.analyst;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads the analyst reports' figures from the reporting schema: reporting.client_months (one row
 * per client per UTC month they ordered in) for activity and value, reporting.orders where a
 * report needs single orders, and reporting.clients for sign-ups. Plain SQL rather than JPA: every
 * query is an aggregate, and this service never writes. Months are UTC throughout, and every
 * range is half-open, from the window's first month up to but not including the month after it.
 */
@Repository
public class AnalystReportRepository {

    // A client is active in a month they had an order execute in, FILLED or REJECTED: that is
    // every client_months row (CANCELLED and EXPIRED orders never reach it). Volume and filled
    // orders count FILLED orders only.
    private static final String MONTHLY_TOTALS_SQL = """
            SELECT month,
                   count(*) AS active_clients,
                   sum(filled_count) AS filled_orders,
                   sum(filled_value) AS volume
            FROM reporting.client_months
            WHERE month >= :from AND month < :to
            GROUP BY month
            """;

    private static final String REGISTRATIONS_SQL = """
            SELECT date_trunc('month', registered_at AT TIME ZONE 'UTC')::date AS month,
                   count(*) AS clients
            FROM reporting.clients
            WHERE registered_at >= :fromInstant AND registered_at < :toInstant
            GROUP BY 1
            """;

    // previous_month is the client's last active month before this one, over all their history,
    // so a client whose first ever month falls in the window is new rather than reactivated.
    private static final String CLIENT_ACTIVITY_SQL = """
            WITH activity AS (
                SELECT month,
                       lag(month) OVER (PARTITION BY client_id ORDER BY month) AS previous_month
                FROM reporting.client_months
                WHERE month < :to
            )
            SELECT month,
                   count(*) FILTER (WHERE previous_month IS NULL) AS new_clients,
                   count(*) FILTER (WHERE previous_month = (month - interval '1 month')::date) AS returning_clients,
                   count(*) FILTER (WHERE previous_month < (month - interval '1 month')::date) AS reactivated_clients
            FROM activity
            WHERE month >= :from
            GROUP BY month
            """;

    // Lapsed in a month: active the month before, not active in it. Reported against the month
    // the client went quiet in, so the range starts and ends a month earlier.
    private static final String LAPSED_SQL = """
            SELECT (earlier.month + interval '1 month')::date AS month,
                   count(*) AS clients
            FROM reporting.client_months earlier
            WHERE earlier.month >= :from AND earlier.month < :to
              AND NOT EXISTS (
                  SELECT 1 FROM reporting.client_months later
                  WHERE later.client_id = earlier.client_id
                    AND later.month = (earlier.month + interval '1 month')::date)
            GROUP BY 1
            """;

    // Each cohort's active clients per month from sign-up on. An order dated before its client's
    // sign-up month can only be bad data, and is left out rather than shown as month -1.
    private static final String COHORT_ACTIVITY_SQL = """
            WITH cohorts AS (
                SELECT client_id,
                       date_trunc('month', registered_at AT TIME ZONE 'UTC')::date AS cohort
                FROM reporting.clients
                WHERE registered_at >= :fromInstant AND registered_at < :toInstant
            )
            SELECT cohorts.cohort, client_months.month, count(*) AS active_clients
            FROM cohorts
            JOIN reporting.client_months client_months ON client_months.client_id = cohorts.client_id
            WHERE client_months.month >= cohorts.cohort AND client_months.month < :to
            GROUP BY 1, 2
            """;

    // First order is the first executed one (FILLED or REJECTED) by when it was placed
    // (submitted_at), matching the definition of an active client. The median is over the clients who have ordered: the FILTER
    // is needed because GREATEST, which guards against an order dated before its sign-up, turns
    // a client with no order (NULL) into 0 days rather than leaving them out.
    private static final String ACTIVATION_SQL = """
            WITH cohorts AS (
                SELECT clients.registered_at,
                       date_trunc('month', clients.registered_at AT TIME ZONE 'UTC')::date AS cohort,
                       (SELECT min(orders.submitted_at)
                        FROM reporting.orders orders
                        WHERE orders.client_id = clients.client_id
                          AND orders.status IN ('FILLED', 'REJECTED')) AS first_order_at
                FROM reporting.clients clients
                WHERE clients.registered_at >= :fromInstant AND clients.registered_at < :toInstant
            )
            SELECT cohort,
                   count(*) AS registered,
                   count(*) FILTER (WHERE first_order_at < registered_at + interval '30 days') AS ordered_within_30_days,
                   count(first_order_at) AS ordered_ever,
                   percentile_cont(0.5) WITHIN GROUP (
                       ORDER BY greatest(0, extract(epoch FROM first_order_at - registered_at) / 86400)::float8
                   ) FILTER (WHERE first_order_at IS NOT NULL) AS median_days_to_first_order
            FROM cohorts
            GROUP BY cohort
            """;

    // Ranks each month's clients by filled value and sums the top 1, 10 and 20 percent of them,
    // rounding each group up to a whole client. Clients with no filled value that month (only
    // rejected orders) traded nothing, so they are not counted among the month's traders.
    private static final String CONCENTRATION_SQL = """
            WITH ranked AS (
                SELECT month, filled_value,
                       row_number() OVER (PARTITION BY month ORDER BY filled_value DESC, client_id) AS rank,
                       count(*) OVER (PARTITION BY month) AS traders
                FROM reporting.client_months
                WHERE month >= :from AND month < :to AND filled_value > 0
            )
            SELECT month,
                   max(traders) AS traders,
                   sum(filled_value) AS volume,
                   sum(filled_value) FILTER (WHERE rank <= ceil(traders * 0.01)) AS top_1_pct,
                   sum(filled_value) FILTER (WHERE rank <= ceil(traders * 0.10)) AS top_10_pct,
                   sum(filled_value) FILTER (WHERE rank <= ceil(traders * 0.20)) AS top_20_pct
            FROM ranked
            GROUP BY month
            """;

    // Single filled orders, valued at quantity times fill price. The band CASE is built from
    // OrderSizeBand's own bounds, so the two can't drift apart.
    private static final String FILLED_ORDER_VALUES = """
            SELECT date_trunc('month', completed_at AT TIME ZONE 'UTC')::date AS month,
                   quantity * fill_price AS value
            FROM reporting.orders
            WHERE status = 'FILLED' AND fill_price IS NOT NULL
              AND completed_at >= :fromInstant AND completed_at < :toInstant
            """;

    private static final String ORDER_SIZE_BANDS_SQL = """
            SELECT month, %s AS band, count(*) AS orders, sum(value) AS value
            FROM (%s) filled
            GROUP BY 1, 2
            """.formatted(bandCase(), FILLED_ORDER_VALUES);

    private static final String ORDER_SIZE_STATS_SQL = """
            SELECT month,
                   count(*) AS orders,
                   avg(value) AS mean,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY value::float8) AS median
            FROM (%s) filled
            GROUP BY month
            """.formatted(FILLED_ORDER_VALUES);

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Constructs an instance of AnalystReportRepository.
     * @param jdbc the JDBC template used to query the reporting schema
     */
    public AnalystReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Active clients, filled orders and traded volume for each month that had any activity.
     * @param window the months to report on
     * @return one row per month with activity, in no particular order
     */
    public List<MonthlyTotals> findMonthlyTotals(ReportWindow window) {
        return jdbc.query(MONTHLY_TOTALS_SQL, months(window), (rs, rowNum) -> new MonthlyTotals(
                month(rs, "month"),
                rs.getLong("active_clients"),
                rs.getLong("filled_orders"),
                rs.getBigDecimal("volume")));
    }

    /**
     * Client sign-ups for each month that had any: each sign-up month's cohort size.
     * @param window the months to report on
     * @return one row per month with sign-ups, in no particular order
     */
    public List<MonthlyCount> findRegistrations(ReportWindow window) {
        return jdbc.query(REGISTRATIONS_SQL, instants(window), (rs, rowNum) -> monthlyCount(rs));
    }

    /**
     * Active clients each month, split by what they did the month before.
     * @param window the months to report on
     * @return one row per month with activity, in no particular order
     */
    public List<ClientActivity> findClientActivity(ReportWindow window) {
        return jdbc.query(CLIENT_ACTIVITY_SQL, months(window), (rs, rowNum) -> new ClientActivity(
                month(rs, "month"),
                rs.getLong("new_clients"),
                rs.getLong("returning_clients"),
                rs.getLong("reactivated_clients")));
    }

    /**
     * Clients who were active the month before and not in the month itself.
     * @param window the months to report on
     * @return one row per month anyone lapsed in, in no particular order
     */
    public List<MonthlyCount> findLapsedClients(ReportWindow window) {
        var params = new MapSqlParameterSource()
                .addValue("from", window.startDate().minusMonths(1))
                .addValue("to", window.endDate().minusMonths(1));
        return jdbc.query(LAPSED_SQL, params, (rs, rowNum) -> monthlyCount(rs));
    }

    /**
     * How many of each cohort were active in each month from their sign-up month to the window's end.
     * @param window the sign-up months, which are also the activity months, to report on
     * @return one row per cohort and month with activity, in no particular order
     */
    public List<CohortActivity> findCohortActivity(ReportWindow window) {
        var params = instants(window).addValue("to", window.endDate());
        return jdbc.query(COHORT_ACTIVITY_SQL, params, (rs, rowNum) -> new CohortActivity(
                month(rs, "cohort"),
                month(rs, "month"),
                rs.getLong("active_clients")));
    }

    /**
     * For each sign-up month, how many of its clients placed an order, and how soon.
     * @param window the sign-up months to report on
     * @return one row per month with sign-ups, in no particular order
     */
    public List<Activation> findActivation(ReportWindow window) {
        return jdbc.query(ACTIVATION_SQL, instants(window), (rs, rowNum) -> new Activation(
                month(rs, "cohort"),
                rs.getLong("registered"),
                rs.getLong("ordered_within_30_days"),
                rs.getLong("ordered_ever"),
                rs.getBigDecimal("median_days_to_first_order")));
    }

    /**
     * Each month's traded volume and how much of it the top clients by volume account for.
     * @param window the months to report on
     * @return one row per month with any filled order, in no particular order
     */
    public List<Concentration> findConcentration(ReportWindow window) {
        return jdbc.query(CONCENTRATION_SQL, months(window), (rs, rowNum) -> new Concentration(
                month(rs, "month"),
                rs.getLong("traders"),
                rs.getBigDecimal("volume"),
                rs.getBigDecimal("top_1_pct"),
                rs.getBigDecimal("top_10_pct"),
                rs.getBigDecimal("top_20_pct")));
    }

    /**
     * Filled orders by value band per month.
     * @param window the months to report on
     * @return one row per month and band with any orders, in no particular order
     */
    public List<BandCount> findOrderSizeBands(ReportWindow window) {
        return jdbc.query(ORDER_SIZE_BANDS_SQL, instants(window), (rs, rowNum) -> new BandCount(
                month(rs, "month"),
                OrderSizeBand.valueOf(rs.getString("band")),
                rs.getLong("orders"),
                rs.getBigDecimal("value")));
    }

    /**
     * The mean and median value of a filled order per month.
     * @param window the months to report on
     * @return one row per month with any filled order, in no particular order
     */
    public List<OrderSizeStats> findOrderSizeStats(ReportWindow window) {
        return jdbc.query(ORDER_SIZE_STATS_SQL, instants(window), (rs, rowNum) -> new OrderSizeStats(
                month(rs, "month"),
                rs.getLong("orders"),
                rs.getBigDecimal("mean"),
                rs.getBigDecimal("median")));
    }

    private static MapSqlParameterSource months(ReportWindow window) {
        return new MapSqlParameterSource()
                .addValue("from", window.startDate())
                .addValue("to", window.endDate());
    }

    private static MapSqlParameterSource instants(ReportWindow window) {
        return new MapSqlParameterSource()
                .addValue("fromInstant", window.startInstant())
                .addValue("toInstant", window.endInstant());
    }

    private static YearMonth month(ResultSet rs, String column) throws SQLException {
        return YearMonth.from(rs.getObject(column, LocalDate.class));
    }

    private static MonthlyCount monthlyCount(ResultSet rs) throws SQLException {
        return new MonthlyCount(month(rs, "month"), rs.getLong("clients"));
    }

    // Literals from OrderSizeBand only, never from a request, so building the SQL is safe. The
    // bands ascend without gaps, so the first upper bound a value is below is its band.
    private static String bandCase() {
        OrderSizeBand[] bands = OrderSizeBand.values();
        String whens = Arrays.stream(bands, 0, bands.length - 1)
                .map(band -> "WHEN value < " + band.to().toPlainString() + " THEN '" + band.name() + "'")
                .collect(Collectors.joining(" "));
        return "CASE " + whens + " ELSE '" + bands[bands.length - 1].name() + "' END";
    }

    /**
     * One month's activity totals.
     * @param month the month
     * @param activeClients the clients who placed an order that month
     * @param filledOrders the orders filled that month
     * @param volume the total value filled that month
     */
    public record MonthlyTotals(YearMonth month, long activeClients, long filledOrders, BigDecimal volume) {}

    /**
     * A number of clients in one month.
     * @param month the month
     * @param clients how many clients
     */
    public record MonthlyCount(YearMonth month, long clients) {}

    /**
     * One month's active clients by what they did the month before.
     * @param month the month
     * @param newClients active for the first time
     * @param returningClients also active the month before
     * @param reactivatedClients active before, but not the month before
     */
    public record ClientActivity(YearMonth month, long newClients, long returningClients, long reactivatedClients) {}

    /**
     * How many of one sign-up month's clients were active in a later month.
     * @param cohort the sign-up month
     * @param month the month they were active in
     * @param activeClients how many of the cohort were active that month
     */
    public record CohortActivity(YearMonth cohort, YearMonth month, long activeClients) {}

    /**
     * One sign-up month's first orders; the median is null when nobody has ordered.
     * @param cohort the sign-up month
     * @param registered how many clients signed up that month
     * @param orderedWithin30Days how many placed their first order within 30 days of signing up
     * @param orderedEver how many have placed an order at all
     * @param medianDaysToFirstOrder the median days from sign-up to first order, or null
     */
    public record Activation(YearMonth cohort, long registered, long orderedWithin30Days, long orderedEver,
                             BigDecimal medianDaysToFirstOrder) {}

    /**
     * One month's traded volume and the share of it from its top clients.
     * @param month the month
     * @param traders the clients with a filled order that month
     * @param volume the total value filled that month
     * @param top1PctVolume the value filled by the top 1% of traders by value, rounded up to a whole client
     * @param top10PctVolume the value filled by the top 10% of traders by value
     * @param top20PctVolume the value filled by the top 20% of traders by value
     */
    public record Concentration(YearMonth month, long traders, BigDecimal volume,
                                BigDecimal top1PctVolume, BigDecimal top10PctVolume, BigDecimal top20PctVolume) {}

    /**
     * Filled orders in one value band in one month.
     * @param month the month
     * @param band the value band
     * @param orders how many filled orders fell in the band
     * @param value their total value
     */
    public record BandCount(YearMonth month, OrderSizeBand band, long orders, BigDecimal value) {}

    /**
     * The mean and median filled order value in one month.
     * @param month the month
     * @param orders how many orders were filled
     * @param mean the mean order value
     * @param median the median order value
     */
    public record OrderSizeStats(YearMonth month, long orders, BigDecimal mean, BigDecimal median) {}
}
