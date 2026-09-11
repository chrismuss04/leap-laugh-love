-- Market Simulation Backend Seed Data
-- Parameters for the GBM price simulation, one row per simulated instrument.
-- Symbols mirror trading.instruments (BTC/USD -> BTCUSD, since a "/" can't appear in a
-- REST path segment without extra URL-encoding handling).
--
-- drift/volatility are annualized (e.g. 0.06 = 6%/year). rng_seed makes each instrument's
-- simulated path reproducible across restarts.
INSERT INTO marketdata.instruments (symbol, display_name, initial_price, drift, volatility, rng_seed, is_active) VALUES
    ('AAPL', 'Apple Inc.', 150.25, 0.07, 0.25, 1001, TRUE),
    ('MSFT', 'Microsoft Corporation', 380.50, 0.08, 0.22, 1002, TRUE),
    ('GOOGL', 'Alphabet Inc.', 140.75, 0.06, 0.28, 1003, TRUE),
    ('TSLA', 'Tesla Inc.', 250.00, 0.05, 0.45, 1004, TRUE),
    ('BTCUSD', 'Bitcoin USD', 65000.00, 0.10, 0.65, 1005, TRUE)
ON CONFLICT (symbol) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    initial_price = EXCLUDED.initial_price,
    drift = EXCLUDED.drift,
    volatility = EXCLUDED.volatility,
    rng_seed = EXCLUDED.rng_seed,
    is_active = EXCLUDED.is_active;
