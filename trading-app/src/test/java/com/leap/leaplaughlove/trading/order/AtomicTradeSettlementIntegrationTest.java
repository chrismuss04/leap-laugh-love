// LLL-133
package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.position.Position;
import com.leap.leaplaughlove.trading.position.PositionMovement;
import com.leap.leaplaughlove.trading.position.PositionMovementRepository;
import com.leap.leaplaughlove.trading.position.PositionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Uses the PostgreSQL test database provisioned by Jenkins, with its production schema.
 * Set TEST_DB_PASSWORD to run locally against a dedicated paysprint test database.
 * Optional TEST_DB_URL and TEST_DB_USER override the Jenkins connection defaults.
 * No test-level transaction: the proxied service must actually commit or roll back.
 * Random fixture IDs isolate tests without deleting append-only audit records.
 */
@SpringBootTest(properties = "spring.sql.init.mode=never")
@EnabledIfEnvironmentVariable(named = "TEST_DB_PASSWORD", matches = ".+")
class AtomicTradeSettlementIntegrationTest {

    @Autowired private OrderSubmissionService orderSubmissionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @SpyBean private PositionRepository positionRepository;
    @SpyBean private PositionMovementRepository positionMovementRepository;

    private UUID accountId;
    private UUID instrumentId;
    private TransactionTemplate transaction;

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
        UUID clientId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        instrumentId = UUID.randomUUID();
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO iam.clients (client_id, email, status) VALUES (?, ?, 'ACTIVE')",
                    clientId, clientId + "@settlement.test");
            jdbc.update("""
                    INSERT INTO trading.accounts
                        (account_id, client_id, account_number, status, base_currency, trading_enabled)
                    VALUES (?, ?, ?, 'ACTIVE', 'USD', TRUE)
                    """, accountId, clientId, "ATOMIC-" + accountId);
            jdbc.update("""
                    INSERT INTO trading.instruments
                        (instrument_id, symbol, instrument_name, asset_class, market, currency, is_tradable)
                    VALUES (?, ?, 'Atomic settlement test', 'EQUITY', 'TEST', 'USD', TRUE)
                    """, instrumentId, "ATOMIC-" + instrumentId);
            jdbc.update("""
                    INSERT INTO trading.cash_ledger (account_id, entry_type, amount, currency)
                    VALUES (?, 'DEPOSIT', 1000.00, 'USD')
                    """, accountId);
        });
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(clientId, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(Order.Side.class)
    void successfulSettlementCommitsAllRecords(Order.Side side) {
        seedHoldingsForSell(side);

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request(side));

        assertEquals("FILLED", response.status());
        transaction.executeWithoutResult(status -> {
            assertSettledAmounts(side);
            assertEquals(1, count("trading.orders"));
            assertEquals(1, count("trading.position_movements"));
            assertEquals(2, count("trading.cash_ledger"));
            assertEquals("FILLED", jdbc.queryForObject(
                    "SELECT status FROM trading.orders WHERE order_id = ?", String.class, response.orderId()));
            assertEquals("FILLED", jdbc.queryForObject(
                    "SELECT status FROM trading.executions WHERE order_id = ?", String.class, response.orderId()));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM trading.cash_ledger c
                    JOIN trading.position_movements p ON p.execution_id = c.execution_id AND p.order_id = c.order_id
                    JOIN trading.executions e ON e.execution_id = c.execution_id AND e.order_id = c.order_id
                    WHERE c.account_id = ? AND c.order_id = ? AND c.execution_id = ?
                    """, Integer.class, accountId, response.orderId(), response.execution().executionId()));
        });
    }

    @ParameterizedTest
    @EnumSource(Order.Side.class)
    void failureAfterCashWriteRollsBackEverything(Order.Side side) {
        seedHoldingsForSell(side);
        var before = snapshot();
        doAnswer(invocation -> {
            entityManager.flush();
            assertCashAfterFill(side); // Prove the cash write reached PostgreSQL before failing.
            throw new SettlementFailure();
        }).when(positionMovementRepository).save(any(PositionMovement.class));

        assertThrows(SettlementFailure.class, () -> orderSubmissionService.submitOrder(request(side)));

        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @EnumSource(Order.Side.class)
    void failureAfterHoldingsWriteRollsBackEverything(Order.Side side) {
        seedHoldingsForSell(side);
        var before = snapshot();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            assertSettledAmounts(side); // Verify both writes before forcing rollback.
            throw new SettlementFailure();
        }).when(positionRepository).save(any(Position.class));

        assertThrows(SettlementFailure.class, () -> orderSubmissionService.submitOrder(request(side)));

        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @EnumSource(Order.Side.class)
    void failureAtCommitRollsBackEverything(Order.Side side) {
        seedHoldingsForSell(side);
        var before = snapshot();
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            entityManager.flush();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    assertSettledAmounts(side);
                    throw new SettlementFailure();
                }
            });
            return saved;
        }).when(positionRepository).save(any(Position.class));

        assertThrows(SettlementFailure.class, () -> orderSubmissionService.submitOrder(request(side)));

        assertEquals(before, snapshot());
    }

    private void seedHoldingsForSell(Order.Side side) {
        if (side == Order.Side.SELL) {
            transaction.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO trading.positions (account_id, instrument_id, quantity, avg_cost)
                    VALUES (?, ?, 10, 20.00)
                    """, accountId, instrumentId));
        }
    }

    private OrderSubmissionRequest request(Order.Side side) {
        return new OrderSubmissionRequest(accountId, null, instrumentId, side, 5, new BigDecimal("25.00"));
    }

    private void assertCashAfterFill(Order.Side side) {
        BigDecimal expected = new BigDecimal(side == Order.Side.BUY ? "875.00" : "1125.00");
        BigDecimal actual = jdbc.queryForObject(
                "SELECT SUM(amount) FROM trading.cash_ledger WHERE account_id = ?", BigDecimal.class, accountId);
        assertEquals(0, expected.compareTo(actual));
    }

    private void assertSettledAmounts(Order.Side side) {
        assertCashAfterFill(side);
        assertEquals(5L, jdbc.queryForObject("""
                SELECT quantity FROM trading.positions WHERE account_id = ? AND instrument_id = ?
                """, Long.class, accountId, instrumentId));
        assertEquals(1, count("trading.position_movements"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE account_id = ?", Integer.class, accountId);
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        return transaction.execute(status -> {
            Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
            for (String table : List.of("orders", "cash_ledger", "position_movements", "positions")) {
                state.put(table, jdbc.queryForList(
                        "SELECT * FROM trading." + table + " WHERE account_id = ? ORDER BY 1", accountId));
            }
            state.put("executions", jdbc.queryForList("""
                    SELECT e.* FROM trading.executions e JOIN trading.orders o ON o.order_id = e.order_id
                    WHERE o.account_id = ? ORDER BY e.execution_id
                    """, accountId));
            return state;
        });
    }

    private static class SettlementFailure extends RuntimeException {
    }
}
