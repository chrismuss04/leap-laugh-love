-- Reporting Order Outcomes: reporting.orders also records orders that waited for their price and
-- were cancelled or expired, not only filled and refused ones. Safe to rerun after successful
-- application. Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- A database set up for native runs (scripts/start-local.ps1) has no reporting schema, so this does
-- nothing there.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-reporting-order-outcomes.sql

BEGIN;

DO $$
BEGIN
    IF to_regclass('reporting.orders') IS NOT NULL THEN
        ALTER TABLE reporting.orders DROP CONSTRAINT IF EXISTS orders_status_check;
        ALTER TABLE reporting.orders
            ADD CONSTRAINT orders_status_check
                CHECK (status IN ('FILLED', 'REJECTED', 'CANCELLED', 'EXPIRED'));
    END IF;
END $$;

COMMIT;
