-- Trade Reconstruction: add the Trading Operations access log. Safe to rerun.
-- Fresh databases get this from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-ops-access-log.sql

BEGIN;

CREATE TABLE IF NOT EXISTS trading.ops_access_log (
    access_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id UUID NOT NULL
        REFERENCES iam.reporting_service_credentials (service_id) ON DELETE RESTRICT,
    action TEXT NOT NULL CHECK (action IN ('SEARCH', 'VIEW_TIMELINE', 'DOWNLOAD_CSV')),
    order_id UUID,
    search_criteria TEXT,
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ops_access_log_order_id
    ON trading.ops_access_log (order_id);

DROP TRIGGER IF EXISTS trg_ops_access_log_no_delete_or_update ON trading.ops_access_log;
CREATE TRIGGER trg_ops_access_log_no_delete_or_update
    BEFORE UPDATE OR DELETE ON trading.ops_access_log
    FOR EACH ROW EXECUTE FUNCTION trading.reject_delete_or_update();

COMMIT;
