-- One-time migration of marketdata.quotes to one row per instrument, as in
-- db/leap_laugh_love_schema.sql, for databases created before it. A fresh database already has
-- it and needs nothing.
--
-- Keeps each instrument's latest quote, drops every older one, swaps the (instrument_id,
-- quote_timestamp) index for the unique index market-data-app upserts on, and gives the space
-- back. Safe to run again: a database that is already migrated is left as is.
--
-- Run it before deploying the market-data-app that upserts quotes - without the unique index
-- every write fails - and with market-data-app stopped, since the old version still inserts a
-- row per tick and would break the unique index. With docker compose:
--
--   docker compose stop market-data-app
--   docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-latest-quotes.sql
--   docker compose start market-data-app

BEGIN;

-- Copying out the few hundred rows worth keeping and truncating is far cheaper than deleting
-- millions of old rows, and TRUNCATE returns the space at once, with no VACUUM FULL.
CREATE TEMP TABLE latest_quotes ON COMMIT DROP AS
    SELECT DISTINCT ON (instrument_id) *
    FROM marketdata.quotes
    ORDER BY instrument_id, quote_timestamp DESC, sequence_number DESC;

TRUNCATE marketdata.quotes;
INSERT INTO marketdata.quotes SELECT * FROM latest_quotes;

DROP INDEX IF EXISTS marketdata.idx_quotes_instrument_quote_timestamp;
CREATE UNIQUE INDEX IF NOT EXISTS uq_quotes_instrument
    ON marketdata.quotes (instrument_id);

COMMIT;

ANALYZE marketdata.quotes;
