-- Trade Reconstruction: add the quote each execution was priced from. Existing executions keep
-- NULL quotes. Safe to rerun. Fresh databases get this from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-execution-quotes.sql

BEGIN;

ALTER TABLE trading.executions ADD COLUMN IF NOT EXISTS quote_bid NUMERIC(18,6);
ALTER TABLE trading.executions ADD COLUMN IF NOT EXISTS quote_ask NUMERIC(18,6);
ALTER TABLE trading.executions ADD COLUMN IF NOT EXISTS quote_last NUMERIC(18,6);
ALTER TABLE trading.executions ADD COLUMN IF NOT EXISTS quote_timestamp TIMESTAMPTZ;
ALTER TABLE trading.executions ADD COLUMN IF NOT EXISTS quote_exchange TEXT;

COMMIT;
