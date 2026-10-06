-- Price Tolerance: add the saved per-account tolerance and the per-order quote it was checked against
-- to an existing database. Safe to rerun after successful application.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-price-tolerance.sql
-- An order is rejected when its execution price is more than max_slippage_pct percent away
-- (either direction) from quoted_price. NULL tolerance means no check.

BEGIN;

-- Price Tolerance: the account's saved default, applied when an order doesn't carry its own.
ALTER TABLE trading.accounts
    ADD COLUMN IF NOT EXISTS max_slippage_pct NUMERIC(5,2)
        CHECK (max_slippage_pct IS NULL OR (max_slippage_pct >= 0 AND max_slippage_pct <= 100));

-- Price Tolerance: what each order was quoted and the tolerance applied, for audit.
ALTER TABLE trading.orders
    ADD COLUMN IF NOT EXISTS quoted_price NUMERIC(18,6)
        CHECK (quoted_price IS NULL OR quoted_price > 0);
ALTER TABLE trading.orders
    ADD COLUMN IF NOT EXISTS max_slippage_pct NUMERIC(5,2)
        CHECK (max_slippage_pct IS NULL OR (max_slippage_pct >= 0 AND max_slippage_pct <= 100));

COMMIT;
