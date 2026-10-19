-- Market Hours: orders know whether they may trade in pre-market and after-hours, and a market
-- order queued while the market is closed carries the price limit (collar) it fills within at the
-- open. Existing orders are regular-hours orders with no collar. Safe to rerun.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-market-hours.sql
-- or on Windows, against a native PostgreSQL:
-- psql -h localhost -U paysprint -d paysprint -v ON_ERROR_STOP=1 -f scripts\migrate-market-hours.sql

BEGIN;

ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS extended_hours BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS collar_price NUMERIC(18,6)
    CHECK (collar_price IS NULL OR collar_price > 0);

COMMIT;
