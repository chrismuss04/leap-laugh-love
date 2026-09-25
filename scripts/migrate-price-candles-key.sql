-- One-time migration of marketdata.price_candles to the natural-key layout in
-- db/leap_laugh_love_schema.sql, for databases created before it. A fresh database already has
-- it and needs nothing.
--
-- Drops the unused candle_id surrogate key and the two indexes that duplicated the natural key,
-- makes (instrument_id, bucket_seconds, bucket_start) the primary key, and adds the index the
-- retention prune needs. Safe to run again: a database that is already migrated is left as is.
--
-- An old database keeps working without this - candle_id has a database default, so the app's
-- inserts still succeed - it just carries the extra indexes.
--
-- Stop market-data-app first: the final VACUUM FULL rewrites the table under an exclusive lock,
-- which blocks the candle flush until it finishes. With docker compose:
--
--   docker compose stop market-data-app
--   docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-price-candles-key.sql
--   docker compose start market-data-app

BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'marketdata' AND table_name = 'price_candles'
                 AND column_name = 'candle_id') THEN
        ALTER TABLE marketdata.price_candles DROP CONSTRAINT price_candles_pkey;
        ALTER TABLE marketdata.price_candles DROP COLUMN candle_id;
        ALTER TABLE marketdata.price_candles
            DROP CONSTRAINT IF EXISTS price_candles_instrument_id_bucket_start_bucket_seconds_key;
        DROP INDEX IF EXISTS marketdata.idx_price_candles_instrument_bucket;
        ALTER TABLE marketdata.price_candles
            ADD PRIMARY KEY (instrument_id, bucket_seconds, bucket_start);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_price_candles_width_bucket
    ON marketdata.price_candles (bucket_seconds, bucket_start);

COMMIT;

-- Dropping a column only hides it; rewriting the table gives its space back.
VACUUM FULL ANALYZE marketdata.price_candles;
