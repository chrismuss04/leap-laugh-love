-- Trailing Stops: add the TRAILING_STOP order type to an existing database. Safe to rerun after
-- successful application. Run scripts/migrate-order-types.sql first.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-trailing-stops.sql

BEGIN;

-- Trailing Stops: the stop follows the best price seen at a fixed amount or percent away.
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS trail_amount NUMERIC(18,6)
    CHECK (trail_amount IS NULL OR trail_amount > 0);
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS trail_percent NUMERIC(5,2)
    CHECK (trail_percent IS NULL OR (trail_percent > 0 AND trail_percent <= 50));
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS trail_peak NUMERIC(18,6)
    CHECK (trail_peak IS NULL OR trail_peak > 0);

ALTER TABLE trading.orders DROP CONSTRAINT IF EXISTS orders_order_type_check;
ALTER TABLE trading.orders
    ADD CONSTRAINT orders_order_type_check
        CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT', 'TRAILING_STOP'));

-- A trailing stop's stop is set once the market price is known, after the order is stored.
ALTER TABLE trading.orders DROP CONSTRAINT IF EXISTS orders_stop_price_by_type;
ALTER TABLE trading.orders
    ADD CONSTRAINT orders_stop_price_by_type
        CHECK ((order_type IN ('STOP', 'STOP_LIMIT')) = (stop_price IS NOT NULL) OR order_type = 'TRAILING_STOP');

ALTER TABLE trading.orders DROP CONSTRAINT IF EXISTS orders_trail_by_type;
ALTER TABLE trading.orders
    ADD CONSTRAINT orders_trail_by_type
        CHECK ((order_type = 'TRAILING_STOP') = (trail_amount IS NOT NULL OR trail_percent IS NOT NULL)
               AND NOT (trail_amount IS NOT NULL AND trail_percent IS NOT NULL)
               AND (trail_peak IS NULL OR order_type = 'TRAILING_STOP'));

-- Reporting keeps the type each completed order was placed as. A database set up for native runs
-- (scripts/start-local.ps1) has no reporting schema, so this is skipped there.
DO $$
BEGIN
    IF to_regclass('reporting.orders') IS NOT NULL THEN
        ALTER TABLE reporting.orders DROP CONSTRAINT IF EXISTS orders_order_type_check;
        ALTER TABLE reporting.orders
            ADD CONSTRAINT orders_order_type_check
                CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT', 'TRAILING_STOP'));
    END IF;
END $$;

COMMIT;
