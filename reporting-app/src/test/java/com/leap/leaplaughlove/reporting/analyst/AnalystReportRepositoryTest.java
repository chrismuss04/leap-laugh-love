package com.leap.leaplaughlove.reporting.analyst;

import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.Activation;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.BandCount;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.ClientActivity;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.CohortActivity;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.Concentration;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.MonthlyCount;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.MonthlyTotals;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportRepository.OrderSizeStats;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * Runs the report SQL against a real Postgres loaded with the schema file: the queries use
 * Postgres-only features H2 can't run. Skipped unless TEST_DB_PASSWORD is set, which CI's Unit
 * Tests stage does. Each test empties the reporting tables and loads its own data inside one
 * transaction that is rolled back afterwards, so it leaves the database as it found it.
 *
 * <p>The main fixture, reported over January to March 2026:
 * <pre>
 *   client  signed up          orders (FILLED unless noted)
 *   A       10 Dec 2025        Dec 1,000 · Jan 500 · Mar 30,000 · 1 Apr 100 (after the window)
 *   B       5 Jan 10:00        6 Jan 10:00 1,000 · Feb REJECTED
 *   C       20 Jan             25 Feb 999.99
 *   D       28 Feb 23:30       1 Mar 11:30 25,000 · 31 Mar 23:59:59 4,999.99
 *   E       1 Mar              none
 * </pre>
 */
@DisplayName("AnalystReportRepository against Postgres")
class AnalystReportRepositoryTest {

    // CI's test database; TEST_DB_URL points the test at another one.
    private static final String DB_URL = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/paysprint");
    private static final ReportWindow Q1 = new ReportWindow(YearMonth.of(2026, 1), YearMonth.of(2026, 3));
    private static final YearMonth JAN = YearMonth.of(2026, 1);
    private static final YearMonth FEB = YearMonth.of(2026, 2);
    private static final YearMonth MAR = YearMonth.of(2026, 3);

    // Numbers compare by value, not scale: Postgres decides how many decimal places a sum carries.
    private static final RecursiveComparisonConfiguration BY_VALUE = RecursiveComparisonConfiguration.builder()
            .withComparatorForType(Comparator.nullsFirst(BigDecimal::compareTo), BigDecimal.class)
            .build();

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private AnalystReportRepository repository;

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();
    private final UUID e = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        String password = System.getenv("TEST_DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isEmpty(),
                "Skipping: TEST_DB_PASSWORD environment variable not set");
        dataSource = new SingleConnectionDataSource(DB_URL, "paysprint", password, true);
        dataSource.setAutoCommit(false);
        jdbc = new JdbcTemplate(dataSource);
        repository = new AnalystReportRepository(new NamedParameterJdbcTemplate(dataSource));

        jdbc.update("DELETE FROM reporting.client_months");
        jdbc.update("DELETE FROM reporting.orders");
        jdbc.update("DELETE FROM reporting.clients");

        client(a, "2025-12-10T09:00:00Z");
        client(b, "2026-01-05T10:00:00Z");
        client(c, "2026-01-20T00:00:00Z");
        client(d, "2026-02-28T23:30:00Z");
        client(e, "2026-03-01T00:00:00Z");
        filled(a, 10, "100", "2025-12-15T12:00:00Z");
        filled(a, 1, "500", "2026-01-10T12:00:00Z");
        filled(a, 100, "300", "2026-03-05T12:00:00Z");
        filled(a, 1, "100", "2026-04-01T00:00:00Z");
        filled(b, 5, "200", "2026-01-06T10:00:00Z");
        order(b, "REJECTED", 3, null, "2026-02-10T12:00:00Z");
        filled(c, 1, "999.99", "2026-02-25T00:00:00Z");
        filled(d, 1, "25000", "2026-03-01T11:30:00Z");
        filled(d, 1, "4999.99", "2026-03-31T23:59:59Z");
        rebuildClientMonths();
    }

    @AfterEach
    void rollBack() throws SQLException {
        if (dataSource != null) {
            dataSource.getConnection().rollback();
            dataSource.destroy();
        }
    }

    private void client(UUID clientId, String registeredAt) {
        jdbc.update("INSERT INTO reporting.clients (client_id, registered_at) VALUES (?, ?)",
                clientId, OffsetDateTime.parse(registeredAt));
    }

    private void filled(UUID clientId, long quantity, String price, String at) {
        order(clientId, "FILLED", quantity, new BigDecimal(price), at);
    }

    /** An order submitted and completed at the same instant. */
    private void order(UUID clientId, String status, long quantity, BigDecimal price, String at) {
        jdbc.update("""
                INSERT INTO reporting.orders (order_id, account_id, client_id, instrument_id, symbol, side,
                    quantity, status, fill_price, submitted_at, completed_at)
                VALUES (?, ?, ?, ?, 'AAPL', 'BUY', ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), clientId, UUID.randomUUID(), quantity, status, price,
                OffsetDateTime.parse(at), OffsetDateTime.parse(at));
    }

    // What reporting-etl keeps up order by order, built here the way the migration rebuilds it.
    private void rebuildClientMonths() {
        jdbc.update("DELETE FROM reporting.client_months");
        jdbc.update("""
                INSERT INTO reporting.client_months (client_id, month, order_count, filled_count, filled_value)
                SELECT client_id, date_trunc('month', completed_at AT TIME ZONE 'UTC')::date, count(*),
                       count(*) FILTER (WHERE status = 'FILLED'),
                       coalesce(sum(quantity * fill_price) FILTER (WHERE status = 'FILLED'), 0)
                FROM reporting.orders
                GROUP BY 1, 2
                """);
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("monthly totals: any order makes a client active; only fills add orders and volume")
    void monthlyTotals() {
        assertThat(repository.findMonthlyTotals(Q1)).usingRecursiveFieldByFieldElementComparator(BY_VALUE)
                .containsExactlyInAnyOrder(
                        new MonthlyTotals(JAN, 2, 2, dec("1500")),
                        new MonthlyTotals(FEB, 2, 1, dec("999.99")),
                        new MonthlyTotals(MAR, 2, 3, dec("59999.99")));
    }

    @Test
    @DisplayName("registrations: counted in the UTC month the client signed up in")
    void registrations() {
        assertThat(repository.findRegistrations(Q1)).containsExactlyInAnyOrder(
                new MonthlyCount(JAN, 2), new MonthlyCount(FEB, 1), new MonthlyCount(MAR, 1));
    }

    @Test
    @DisplayName("client activity: new from a first ever month, returning, reactivated after a gap")
    void clientActivity() {
        assertThat(repository.findClientActivity(Q1)).containsExactlyInAnyOrder(
                // A ordered in December, so is returning, not new.
                new ClientActivity(JAN, 1, 1, 0),
                new ClientActivity(FEB, 1, 1, 0),
                // A skipped February.
                new ClientActivity(MAR, 1, 0, 1));
    }

    @Test
    @DisplayName("lapsed: reported in the first month a client active the month before went quiet")
    void lapsed() {
        assertThat(repository.findLapsedClients(Q1)).containsExactlyInAnyOrder(
                new MonthlyCount(FEB, 1),
                new MonthlyCount(MAR, 2));
    }

    @Test
    @DisplayName("cohort activity: each sign-up month's active clients per month, until the window ends")
    void cohortActivity() {
        assertThat(repository.findCohortActivity(Q1)).containsExactlyInAnyOrder(
                new CohortActivity(JAN, JAN, 1),
                new CohortActivity(JAN, FEB, 2),
                // D signed up on 28 February and first ordered in March.
                new CohortActivity(FEB, MAR, 1));
    }

    @Test
    @DisplayName("activation: first orders within 30 days, ever, and the median days to one")
    void activation() {
        assertThat(repository.findActivation(Q1)).usingRecursiveFieldByFieldElementComparator(BY_VALUE)
                .containsExactlyInAnyOrder(
                        // B after 1 day, C after 36.
                        new Activation(JAN, 2, 1, 2, dec("18.5")),
                        new Activation(FEB, 1, 1, 1, dec("0.5")),
                        new Activation(MAR, 1, 0, 0, null));
    }

    @Test
    @DisplayName("concentration: rejected-only clients are not traders; tiers round up to whole clients")
    void concentration() {
        assertThat(repository.findConcentration(Q1)).usingRecursiveFieldByFieldElementComparator(BY_VALUE)
                .containsExactlyInAnyOrder(
                        new Concentration(JAN, 2, dec("1500"), dec("1000"), dec("1000"), dec("1000")),
                        new Concentration(FEB, 1, dec("999.99"), dec("999.99"), dec("999.99"), dec("999.99")),
                        new Concentration(MAR, 2, dec("59999.99"), dec("30000"), dec("30000"), dec("30000")));
    }

    @Test
    @DisplayName("concentration: the top 1, 10 and 20 percent of eleven traders are 1, 2 and 3 clients")
    void concentrationTiers() {
        YearMonth may = YearMonth.of(2025, 5);
        for (int value = 100; value <= 1100; value += 100) {
            jdbc.update("INSERT INTO reporting.client_months VALUES (?, '2025-05-01', 1, 1, ?)", UUID.randomUUID(), value);
        }
        // Only rejected orders: active, but not trading.
        jdbc.update("INSERT INTO reporting.client_months VALUES (?, '2025-05-01', 1, 0, 0)", UUID.randomUUID());

        assertThat(repository.findConcentration(new ReportWindow(may, may)))
                .usingRecursiveFieldByFieldElementComparator(BY_VALUE)
                .containsExactly(new Concentration(may, 11, dec("6600"), dec("1100"), dec("2100"), dec("3000")));
    }

    @Test
    @DisplayName("order sizes: each filled order in the band its value falls in")
    void orderSizeBands() {
        assertThat(repository.findOrderSizeBands(Q1)).usingRecursiveFieldByFieldElementComparator(BY_VALUE)
                .containsExactlyInAnyOrder(
                        new BandCount(JAN, OrderSizeBand.UNDER_1K, 1, dec("500")),
                        new BandCount(JAN, OrderSizeBand.FROM_1K, 1, dec("1000")),
                        new BandCount(FEB, OrderSizeBand.UNDER_1K, 1, dec("999.99")),
                        // 23:59:59 on 31 March is still March.
                        new BandCount(MAR, OrderSizeBand.FROM_1K, 1, dec("4999.99")),
                        new BandCount(MAR, OrderSizeBand.FROM_25K, 2, dec("55000")));
    }

    @Test
    @DisplayName("order sizes: a band includes its lower bound and stops short of its upper one")
    void orderSizeBandEdges() {
        UUID trader = UUID.randomUUID();
        for (String value : List.of("999.99", "1000", "4999.99", "5000", "9999.99", "10000", "24999.99", "25000")) {
            filled(trader, 1, value, "2025-06-15T12:00:00Z");
        }
        YearMonth june = YearMonth.of(2025, 6);

        assertThat(repository.findOrderSizeBands(new ReportWindow(june, june)))
                .usingRecursiveFieldByFieldElementComparator(BY_VALUE)
                .containsExactlyInAnyOrder(
                        new BandCount(june, OrderSizeBand.UNDER_1K, 1, dec("999.99")),
                        new BandCount(june, OrderSizeBand.FROM_1K, 2, dec("5999.99")),
                        new BandCount(june, OrderSizeBand.FROM_5K, 2, dec("14999.99")),
                        new BandCount(june, OrderSizeBand.FROM_10K, 2, dec("34999.99")),
                        new BandCount(june, OrderSizeBand.FROM_25K, 1, dec("25000")));
    }

    @Test
    @DisplayName("order sizes: mean and median filled order value per month")
    void orderSizeStats() {
        List<OrderSizeStats> stats = repository.findOrderSizeStats(Q1);

        assertThat(stats).extracting(OrderSizeStats::month, OrderSizeStats::orders)
                .containsExactlyInAnyOrder(
                        tuple(JAN, 2L),
                        tuple(FEB, 1L),
                        tuple(MAR, 3L));
        OrderSizeStats march = stats.stream().filter(row -> row.month().equals(MAR)).findFirst().orElseThrow();
        assertThat(march.mean()).isCloseTo(dec("19999.9967"), within(dec("0.0001")));
        assertThat(march.median()).isEqualByComparingTo("25000");
        OrderSizeStats january = stats.stream().filter(row -> row.month().equals(JAN)).findFirst().orElseThrow();
        assertThat(january.mean()).isEqualByComparingTo("750");
        assertThat(january.median()).isEqualByComparingTo("750");
    }
}
