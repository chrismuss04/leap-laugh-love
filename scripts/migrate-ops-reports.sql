-- Trading Ops Reports: log the new report lookups, and index the orders they read across every
-- client. Safe to rerun. Fresh databases get this from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-ops-reports.sql

BEGIN;

ALTER TABLE trading.ops_access_log DROP CONSTRAINT IF EXISTS ops_access_log_action_check;
ALTER TABLE trading.ops_access_log
    ADD CONSTRAINT ops_access_log_action_check
        CHECK (action IN ('SEARCH', 'VIEW_TIMELINE', 'DOWNLOAD_CSV', 'VIEW_ORDER',
                          'VIEW_ORDER_COUNTS', 'VIEW_REJECTIONS', 'VIEW_STUCK_ORDERS'));

CREATE INDEX IF NOT EXISTS idx_orders_awaiting_execution_submitted_at
    ON trading.orders (submitted_at) WHERE status IN ('SUBMITTED', 'ACCEPTED');
CREATE INDEX IF NOT EXISTS idx_orders_rejected_submitted_at
    ON trading.orders (submitted_at) WHERE status = 'REJECTED';

COMMIT;
