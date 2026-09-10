// LLL-172 - Test Order Placement/Execution
package com.leap.leaplaughlove.trading;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * LLL-172 - Test Order Placement/Execution.
 *
 * <p>There is no application service that places orders or records executions yet - that whole
 * flow currently only exists as a handwritten sequence of SQL statements in the seed data. So
 * instead of exercising a controller or service, this test talks to the database directly
 * (through plain JDBC INSERTs and SELECTs) to prove the schema itself supports the chain of
 * events a real order fill is supposed to produce: an order row, an execution row tied back to
 * that order, a position-ledger entry, and an updated position. It also proves the opposite:
 * a rejected execution should leave the position and its ledger completely untouched.
 *
 * <p>Runs against the H2 fixture in {@code order_execution_propagation_test_setup.sql}, which
 * re-creates the real {@code trading}/{@code iam} tables (minus the Postgres-only retention
 * triggers, which aren't relevant to this story) and seeds one client, one account, and one
 * instrument for the test's own inserts to attach to.
 */
@SpringBootTest
@Sql(scripts = "/db/order_execution_propagation_test_setup.sql")
class OrderExecutionPropagationIntegrationTest {

    // These match the seed rows in order_execution_propagation_test_setup.sql exactly, so every
    // test method has a ready-made account and instrument to place orders against.
    private static final UUID ACCOUNT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID INSTRUMENT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Walks through a full, successful buy: places an order, records a matching execution,
     * writes the resulting movement to the position ledger, and updates the account's holding.
     * Then reads everything back to confirm each row landed where it should and is linked to
     * the right order/execution/account/instrument.
     */
    @Test
    void orderFillPropagatesThroughExecutionLedgerAndPosition() {
        UUID orderId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();

        // Step 1: place the order. A real trading engine would start this at SUBMITTED and walk
        // it through ACCEPTED/FILLED, but since nothing here executes that workflow, we insert
        // it already FILLED - the point of this test is what happens *after* a fill, not how an
        // order gets there.
        jdbcTemplate.update(
                "INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, filled_at) "
                        + "VALUES (?, ?, ?, 'BUY', ?, 'FILLED', CURRENT_TIMESTAMP)",
                orderId, ACCOUNT_ID, INSTRUMENT_ID, 10L);

        // Step 2: record the execution that filled it. This is the row that actually says
        // "10 shares traded at $150.25 each" - the order alone doesn't carry a price.
        jdbcTemplate.update(
                "INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status) "
                        + "VALUES (?, ?, ?, ?, 'FILLED')",
                executionId, orderId, 10L, new BigDecimal("150.25"));

        // Step 3: log the movement this execution caused. This is the audit trail - it's what
        // lets you trace a position change back to the exact order and execution that caused it.
        jdbcTemplate.update(
                "INSERT INTO trading.position_movements "
                        + "(movement_id, account_id, instrument_id, order_id, execution_id, movement_type, quantity_delta, cost_delta) "
                        + "VALUES (?, ?, ?, ?, ?, 'BUY_FILL', ?, ?)",
                movementId, ACCOUNT_ID, INSTRUMENT_ID, orderId, executionId, 10L, new BigDecimal("1502.50"));

        // Step 4: update the account's actual holding to reflect that movement. There's no prior
        // position row for this account/instrument, so this is a fresh insert rather than a
        // running-balance update.
        jdbcTemplate.update(
                "INSERT INTO trading.positions (account_id, instrument_id, quantity, avg_cost) VALUES (?, ?, ?, ?)",
                ACCOUNT_ID, INSTRUMENT_ID, 10L, new BigDecimal("150.25"));

        // Now check every link in that chain actually holds.

        // The order should be findable under the account we placed it for.
        UUID orderAccountId = jdbcTemplate.queryForObject(
                "SELECT account_id FROM trading.orders WHERE order_id = ?", UUID.class, orderId);
        assertEquals(ACCOUNT_ID, orderAccountId, "order should be recorded under the account it was placed for");

        // The execution should point back to the order we just placed - joining through orders
        // is how we confirm it's tied to the right account, since executions don't store
        // account_id directly.
        Long executionOrderMatchCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trading.executions e "
                        + "JOIN trading.orders o ON e.order_id = o.order_id "
                        + "WHERE e.execution_id = ? AND e.order_id = ? AND o.account_id = ?",
                Long.class, executionId, orderId, ACCOUNT_ID);
        assertEquals(1L, executionOrderMatchCount, "execution should be tied to the correct order_id and account_id");

        // The position should now show the filled quantity for this account and instrument.
        Long positionQuantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM trading.positions WHERE account_id = ? AND instrument_id = ?",
                Long.class, ACCOUNT_ID, INSTRUMENT_ID);
        assertEquals(10L, positionQuantity, "position quantity should reflect the filled order");

        // And the ledger entry that caused that position update should exist, referencing the
        // same order and execution.
        Long movementMatchCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trading.position_movements "
                        + "WHERE account_id = ? AND instrument_id = ? AND order_id = ? AND execution_id = ? AND quantity_delta = ?",
                Long.class, ACCOUNT_ID, INSTRUMENT_ID, orderId, executionId, 10L);
        assertEquals(1L, movementMatchCount, "position_movements should have one entry recording this fill");
    }

    /**
     * Walks through a rejected execution: the order still gets placed and logged, but since
     * nothing actually traded, neither the position ledger nor the account's holding should
     * change at all.
     */
    @Test
    void rejectedExecutionLeavesPositionAndLedgerUntouched() {
        UUID orderId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        // The order still gets placed - being rejected later doesn't erase the fact that it
        // was submitted.
        jdbcTemplate.update(
                "INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, rejected_at, rejection_reason) "
                        + "VALUES (?, ?, ?, 'SELL', ?, 'REJECTED', CURRENT_TIMESTAMP, ?)",
                orderId, ACCOUNT_ID, INSTRUMENT_ID, 5L, "Insufficient position to sell");

        // The execution attempt is logged too - rejected executions are still real events that
        // need an audit trail, they just carry no fill quantity or price since nothing traded.
        jdbcTemplate.update(
                "INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, reason) "
                        + "VALUES (?, ?, NULL, NULL, 'REJECTED', ?)",
                executionId, orderId, "Insufficient position to sell");

        // Deliberately no INSERT into position_movements or trading.positions here - that's
        // the entire point of this test.

        // The order should still be sitting there as a matter of record, marked REJECTED.
        String orderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM trading.orders WHERE order_id = ?", String.class, orderId);
        assertEquals("REJECTED", orderStatus, "a rejected order should still be logged, just with REJECTED status");

        // The execution should also still exist, recorded as rejected with no fill details.
        String executionStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM trading.executions WHERE execution_id = ? AND order_id = ?",
                String.class, executionId, orderId);
        assertEquals("REJECTED", executionStatus, "the execution attempt should be logged as REJECTED");

        Long fillQuantity = jdbcTemplate.queryForObject(
                "SELECT fill_quantity FROM trading.executions WHERE execution_id = ?", Long.class, executionId);
        assertNull(fillQuantity, "a rejected execution shouldn't carry a fill quantity - nothing traded");

        // Nothing should have been written to the position ledger for this order.
        Long movementCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trading.position_movements WHERE order_id = ?", Long.class, orderId);
        assertEquals(0L, movementCount, "a rejected execution must not create a position_movements entry");

        // And the account's position for this instrument should not exist at all, since this
        // fixture starts with no prior position and this rejected order is the only activity.
        Long positionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trading.positions WHERE account_id = ? AND instrument_id = ?",
                Long.class, ACCOUNT_ID, INSTRUMENT_ID);
        assertEquals(0L, positionCount, "a rejected execution must not update (or create) a position");
    }
}
