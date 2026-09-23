// LLL-133
package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.balance.BalanceService;
import com.leap.leaplaughlove.trading.balance.CashMovementRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses Jenkins's PostgreSQL test database and production schema, like the atomicity tests.
 * Each worker has its own authentication, connection, and transaction. The first worker
 * holds its completed writes uncommitted until PostgreSQL confirms the second is waiting
 * for its lock. Bounded waits ensure a broken locking implementation fails instead of hanging.
 */
@SpringBootTest(properties = "spring.sql.init.mode=never")
@EnabledIfEnvironmentVariable(named = "TEST_DB_PASSWORD", matches = ".+")
class ConcurrentTradeSettlementIntegrationTest {

    @Autowired private OrderSubmissionService orderSubmissionService;
    @Autowired private BalanceService balanceService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID clientId;
    private UUID accountId;
    private UUID instrumentId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault(
                "TEST_DB_URL", "jdbc:postgresql://localhost:5432/paysprint"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "paysprint"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void setUp() {
        clientId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        instrumentId = UUID.randomUUID();
        transaction().executeWithoutResult(status -> {
            jdbc.update("INSERT INTO iam.clients (client_id, email, status) VALUES (?, ?, 'ACTIVE')",
                    clientId, clientId + "@concurrent.test");
            jdbc.update("""
                    INSERT INTO trading.accounts
                        (account_id, client_id, account_number, status, base_currency, trading_enabled)
                    VALUES (?, ?, ?, 'ACTIVE', 'USD', TRUE)
                    """, accountId, clientId, "CONCURRENT-" + accountId);
            jdbc.update("""
                    INSERT INTO trading.instruments
                        (instrument_id, symbol, instrument_name, asset_class, market, currency, is_tradable)
                    VALUES (?, ?, 'Concurrent settlement test', 'EQUITY', 'TEST', 'USD', TRUE)
                    """, instrumentId, "CONCURRENT-" + instrumentId);
            jdbc.update("""
                    INSERT INTO trading.cash_ledger (account_id, entry_type, amount, currency)
                    VALUES (?, 'DEPOSIT', 1000.00, 'USD')
                    """, accountId);
        });
    }

    @Test
    void competingBuysCannotSpendTheSameCash() throws Exception {
        assertEquals(List.of("FILLED", "REJECTED"), race(
                () -> trade(Order.Side.BUY, 30), () -> trade(Order.Side.BUY, 30)));
        assertState("250.00", 30, 1, 1, 0);
    }

    @Test
    void competingSellsCannotSellTheSameShares() throws Exception {
        transaction().executeWithoutResult(status -> jdbc.update("""
                INSERT INTO trading.positions (account_id, instrument_id, quantity, avg_cost)
                VALUES (?, ?, 10, 20.00)
                """, accountId, instrumentId));
        assertEquals(List.of("FILLED", "REJECTED"), race(
                () -> trade(Order.Side.SELL, 7), () -> trade(Order.Side.SELL, 7)));
        assertState("1175.00", 3, 1, 1, 0);
    }

    @Test
    void affordableBuysAccumulateHoldingsWithoutLostUpdates() throws Exception {
        assertEquals(List.of("FILLED", "FILLED"), race(
                () -> trade(Order.Side.BUY, 5), () -> trade(Order.Side.BUY, 7)));
        assertState("700.00", 12, 2, 0, 0);
    }

    @Test
    void withdrawalWaitsForTradeAndRechecksBalance() throws Exception {
        assertEquals(List.of("FILLED", "WITHDRAWAL_REJECTED"), race(
                () -> trade(Order.Side.BUY, 30), this::withdraw));
        assertState("250.00", 30, 1, 0, 0);
    }

    @Test
    void tradeWaitsForWithdrawalAndRechecksBalance() throws Exception {
        assertEquals(List.of("WITHDRAWN", "REJECTED"), race(
                this::withdraw, () -> trade(Order.Side.BUY, 30)));
        assertState("250.00", 0, 0, 1, 1);
    }

    private String trade(Order.Side side, long quantity) {
        return orderSubmissionService.submitOrder(new OrderSubmissionRequest(
                accountId, null, instrumentId, side, quantity, new BigDecimal("25.00"))).status();
    }

    private String withdraw() {
        balanceService.withdraw(accountId, new CashMovementRequest(new BigDecimal("750.00"), "Concurrent test"));
        return "WITHDRAWN";
    }

    private List<String> race(Supplier<String> first, Supplier<String> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var firstWritesComplete = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var firstPid = new AtomicInteger();
        var secondPid = new AtomicInteger();
        try {
            var firstResult = executor.submit(() -> authenticatedTransaction(() -> {
                firstPid.set(backendPid());
                String result = first.get();
                firstWritesComplete.countDown();
                await(releaseFirst);
                return result;
            }));
            await(firstWritesComplete);
            var secondResult = executor.submit(() -> authenticatedTransaction(() -> {
                secondPid.set(backendPid());
                secondStarted.countDown();
                return second.get();
            }));
            await(secondStarted);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean blocked = false;
            while (System.nanoTime() < deadline && !secondResult.isDone()) {
                blocked = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, firstPid.get(), secondPid.get()));
                if (blocked) {
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(blocked, "Second operation must wait for the first account transaction in PostgreSQL");
            assertFalse(secondResult.isDone(), "Waiting operation must not complete before the first commits");
            releaseFirst.countDown();
            return List.of(firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS), "Database workers must terminate");
        }
    }

    private String authenticatedTransaction(Supplier<String> operation) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(clientId, null, List.of()));
        try {
            return transaction().execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '15s'");
                jdbc.execute("SET LOCAL statement_timeout = '20s'");
                return operation.get();
            });
        } catch (ResponseStatusException ex) {
            // Catch outside the transaction so a rejected withdrawal finishes rolling back.
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            assertEquals("Withdrawal amount cannot exceed available account balance", ex.getReason());
            return "WITHDRAWAL_REJECTED";
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private int backendPid() {
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private TransactionTemplate transaction() {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(30);
        return template;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out coordinating database workers");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Database worker interrupted", ex);
        }
    }

    private void assertState(String cash, long shares, int fills, int rejections, int withdrawals) {
        transaction().executeWithoutResult(status -> {
            assertEquals(0, new BigDecimal(cash).compareTo(jdbc.queryForObject(
                    "SELECT SUM(amount) FROM trading.cash_ledger WHERE account_id = ?", BigDecimal.class, accountId)));
            assertEquals(shares, jdbc.queryForObject(
                    "SELECT COALESCE(SUM(quantity), 0) FROM trading.positions WHERE account_id = ?", Long.class, accountId));
            assertEquals(fills, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM trading.orders WHERE account_id = ? AND status = 'FILLED'", Integer.class, accountId));
            assertEquals(rejections, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM trading.orders WHERE account_id = ? AND status = 'REJECTED'", Integer.class, accountId));
            assertEquals(fills, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM trading.position_movements WHERE account_id = ?", Integer.class, accountId));
            assertEquals(fills, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM trading.cash_ledger c
                    JOIN trading.position_movements p ON p.execution_id = c.execution_id AND p.order_id = c.order_id
                    JOIN trading.executions e ON e.execution_id = c.execution_id AND e.order_id = c.order_id
                    WHERE c.account_id = ? AND e.status = 'FILLED'
                    """, Integer.class, accountId));
            assertEquals(1 + fills + withdrawals, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM trading.cash_ledger WHERE account_id = ?", Integer.class, accountId));
        });
    }
}
