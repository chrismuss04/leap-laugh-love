package com.leap.leaplaughlove.order.ops;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Records each Trading Operations lookup in trading.ops_access_log. Callers write the entry
 * before reading any trade data, so a failed write means nothing is returned unrecorded.
 */
@Repository
public class OpsAccessLog {

    public enum Action { SEARCH, VIEW_TIMELINE, DOWNLOAD_CSV }

    private final JdbcTemplate jdbc;

    public OpsAccessLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The staff member is the signed-in principal: a staff token's subject is its service_id. */
    public void record(Action action, UUID orderId, String searchCriteria) {
        UUID staffId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        jdbc.update("""
                INSERT INTO trading.ops_access_log (access_id, staff_id, action, order_id, search_criteria)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), staffId, action.name(), orderId, searchCriteria);
    }
}
