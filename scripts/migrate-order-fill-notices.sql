-- Order Fill Emails: track which finished orders still owe the client an email. Safe to rerun
-- after successful application. Run scripts/migrate-order-types.sql first.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-order-fill-notices.sql

BEGIN;

-- Set when an order goes OPEN; cleared once its outcome is emailed or the client opted out.
-- Orders that were already finished when this ran are left alone, so nobody is mailed history.
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS notice_pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS notified_at TIMESTAMPTZ;

UPDATE trading.orders SET notice_pending = TRUE WHERE status = 'OPEN' AND NOT notice_pending;

CREATE INDEX IF NOT EXISTS idx_orders_notice_pending
    ON trading.orders (order_id) WHERE notice_pending;

COMMIT;
