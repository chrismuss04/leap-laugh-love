-- Order Types: add limit, stop and stop-limit orders, which wait OPEN until the market reaches
-- their price, to an existing database. Safe to rerun after successful application.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-order-types.sql

BEGIN;

-- Order Types: waiting orders are OPEN, and end CANCELLED or EXPIRED if they never fill.
ALTER TABLE trading.orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE trading.orders
    ADD CONSTRAINT orders_status_check
        CHECK (status IN ('SUBMITTED', 'OPEN', 'ACCEPTED', 'REJECTED', 'FILLED', 'CANCELLED', 'EXPIRED'));

-- Order Types: existing orders were all market orders.
ALTER TABLE trading.orders
    ADD COLUMN IF NOT EXISTS order_type TEXT NOT NULL DEFAULT 'MARKET'
        CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT'));
ALTER TABLE trading.orders
    ADD COLUMN IF NOT EXISTS limit_price NUMERIC(18,6) CHECK (limit_price IS NULL OR limit_price > 0);
ALTER TABLE trading.orders
    ADD COLUMN IF NOT EXISTS stop_price NUMERIC(18,6) CHECK (stop_price IS NULL OR stop_price > 0);
ALTER TABLE trading.orders
    ADD COLUMN IF NOT EXISTS time_in_force TEXT
        CHECK (time_in_force IS NULL OR time_in_force IN ('DAY', 'GTC'));
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS triggered_at TIMESTAMPTZ;
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS expired_at TIMESTAMPTZ;
ALTER TABLE trading.orders
    ADD COLUMN IF NOT EXISTS reserved_cash NUMERIC(18,2) CHECK (reserved_cash IS NULL OR reserved_cash >= 0);

ALTER TABLE trading.orders DROP CONSTRAINT IF EXISTS orders_limit_price_by_type;
ALTER TABLE trading.orders
    ADD CONSTRAINT orders_limit_price_by_type
        CHECK ((order_type IN ('LIMIT', 'STOP_LIMIT')) = (limit_price IS NOT NULL));
ALTER TABLE trading.orders DROP CONSTRAINT IF EXISTS orders_stop_price_by_type;
ALTER TABLE trading.orders
    ADD CONSTRAINT orders_stop_price_by_type
        CHECK ((order_type IN ('STOP', 'STOP_LIMIT')) = (stop_price IS NOT NULL));

-- Order Types: backs OrderTriggerEngine's scan and the per-account holds on cash and shares.
CREATE INDEX IF NOT EXISTS idx_orders_open
    ON trading.orders (account_id, instrument_id) WHERE status = 'OPEN';

-- Order Types: reporting keeps the type each completed order was placed as. A database set up
-- for native runs (scripts/start-local.ps1) has no reporting schema, so this is skipped there.
DO $$
BEGIN
    IF to_regclass('reporting.orders') IS NOT NULL THEN
        ALTER TABLE reporting.orders
            ADD COLUMN IF NOT EXISTS order_type TEXT NOT NULL DEFAULT 'MARKET'
                CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT'));
    END IF;
END $$;

COMMIT;
