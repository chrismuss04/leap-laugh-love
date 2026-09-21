-- H2 mirror of the marketdata DDL, run at datasource initialisation so the tables exist before
-- any bean queries them: the simulation engine, candle accumulator and quote ingestion service
-- all read on startup, which is earlier than an @Sql script can run.
--
-- Kept in step with db/leap_laugh_love_schema.sql. The composite unique key below in particular
-- is the thing PriceHistoryBackfillIntegrationTest exercises, so it must match the real one.
CREATE SCHEMA IF NOT EXISTS marketdata;

CREATE TABLE IF NOT EXISTS marketdata.instruments (
    instrument_id UUID PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    initial_price NUMERIC(18,6) NOT NULL,
    drift NUMERIC(9,6) NOT NULL,
    volatility NUMERIC(9,6) NOT NULL,
    rng_seed BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS marketdata.price_candles (
    candle_id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL
        REFERENCES marketdata.instruments (instrument_id) ON DELETE RESTRICT,
    bucket_start TIMESTAMP WITH TIME ZONE NOT NULL,
    bucket_seconds INTEGER NOT NULL DEFAULT 60,
    open NUMERIC(18,6) NOT NULL,
    high NUMERIC(18,6) NOT NULL,
    low NUMERIC(18,6) NOT NULL,
    close NUMERIC(18,6) NOT NULL,
    UNIQUE (instrument_id, bucket_start, bucket_seconds)
);

CREATE TABLE IF NOT EXISTS marketdata.quotes (
    quote_id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL
        REFERENCES marketdata.instruments (instrument_id) ON DELETE RESTRICT,
    bid_price NUMERIC(18,6) NOT NULL,
    bid_size BIGINT NOT NULL,
    ask_price NUMERIC(18,6) NOT NULL,
    ask_size BIGINT NOT NULL,
    last_price NUMERIC(18,6) NOT NULL,
    last_size BIGINT NOT NULL,
    exchange VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    quote_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
