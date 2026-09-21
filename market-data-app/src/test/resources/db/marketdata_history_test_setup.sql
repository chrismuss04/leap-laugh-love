-- Resets the marketdata tables to a known set of instruments for each test. The DDL lives in
-- schema.sql, which runs early enough for the startup beans that query these tables.
DELETE FROM marketdata.price_candles;
DELETE FROM marketdata.quotes;
DELETE FROM marketdata.instruments;

INSERT INTO marketdata.instruments
    (instrument_id, symbol, display_name, initial_price, drift, volatility, rng_seed, is_active)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'AAPL', 'Apple Inc.', 150.25, 0.07, 0.25, 1001, TRUE),
    ('22222222-2222-2222-2222-222222222222', 'SPX', 'S&P 500', 4650.00, 0.08, 0.15, 2001, TRUE);
