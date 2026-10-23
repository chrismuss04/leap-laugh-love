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

    /** The kind of lookup being recorded. */
    public enum Action {
        /** Searched for trades. */
        SEARCH,
        /** Viewed an order's timeline. */
        VIEW_TIMELINE,
        /** Downloaded an order's timeline as CSV. */
        DOWNLOAD_CSV,
        /** Viewed an order's records. */
        VIEW_ORDER,
        /** Viewed how many orders a search matches, by outcome. */
        VIEW_ORDER_COUNTS,
        /** Viewed the rejections-by-reason report. */
        VIEW_REJECTIONS,
        /** Viewed the stuck-orders report. */
        VIEW_STUCK_ORDERS
    }

    private final JdbcTemplate jdbc;

    /**
     * Constructs a new OpsAccessLog.
     * @param jdbc access to the trading schema
     */
    public OpsAccessLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a lookup. The staff member is the signed-in principal: a staff token's subject is
     * its service_id.
     * @param action the kind of lookup
     * @param orderId the order looked up, or null for searches and reports
     * @param searchCriteria the criteria searched or reported on, or null
     */
    public void record(Action action, UUID orderId, String searchCriteria) {
        UUID staffId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        jdbc.update("""
                INSERT INTO trading.ops_access_log (access_id, staff_id, action, order_id, search_criteria)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), staffId, action.name(), orderId, searchCriteria);
    }
}
