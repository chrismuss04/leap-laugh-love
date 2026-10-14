package com.leap.leaplaughlove.order.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@JdbcTest
@Import(OpsAccessLog.class)
@Sql("/db/order_submission_test_setup.sql")
@DisplayName("OpsAccessLog Tests")
class OpsAccessLogTest {

    private static final UUID STAFF_ID = UUID.randomUUID();

    @Autowired
    private OpsAccessLog accessLog;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Records the signed-in staff member, action, order and criteria")
    void records() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                STAFF_ID, null, List.of(new SimpleGrantedAuthority("ROLE_TRADING_OPERATIONS"))));
        UUID orderId = UUID.randomUUID();

        accessLog.record(OpsAccessLog.Action.VIEW_TIMELINE, orderId, null);
        accessLog.record(OpsAccessLog.Action.SEARCH, null, "{symbol=AAPL}");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT staff_id, action, order_id, search_criteria, accessed_at FROM trading.ops_access_log ORDER BY action DESC");
        assertEquals(2, rows.size());
        assertEquals(STAFF_ID, rows.get(0).get("STAFF_ID"));
        assertEquals("VIEW_TIMELINE", rows.get(0).get("ACTION"));
        assertEquals(orderId, rows.get(0).get("ORDER_ID"));
        assertNull(rows.get(0).get("SEARCH_CRITERIA"));
        assertNotNull(rows.get(0).get("ACCESSED_AT"));
        assertEquals("SEARCH", rows.get(1).get("ACTION"));
        assertNull(rows.get(1).get("ORDER_ID"));
        assertEquals("{symbol=AAPL}", rows.get(1).get("SEARCH_CRITERIA"));
    }
}
