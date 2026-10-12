CREATE SCHEMA IF NOT EXISTS reporting;

DROP TABLE IF EXISTS reporting.orders;

-- Mirrors reporting.orders in leap_laugh_love_schema.sql, minus the CHECKs.
CREATE TABLE reporting.orders (
    order_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    client_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    symbol TEXT NOT NULL,
    side TEXT NOT NULL,
    quantity BIGINT NOT NULL,
    status TEXT NOT NULL,
    fill_price NUMERIC(18,6),
    rejection_reason TEXT,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    loaded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
