-- Market Simulation Backend Seed Data
-- Parameters for the GBM price simulation, one row per simulated instrument.
-- Symbols mirror trading.instruments (BTC/USD -> BTCUSD, since a "/" can't appear in a
-- REST path segment without extra URL-encoding handling).
--
-- drift/volatility are annualized (e.g. 0.06 = 6%/year). rng_seed makes each instrument's
-- simulated path reproducible across restarts.
--
-- Two groups live in this table. The 1000-series symbols mirror trading.instruments and are
-- tradable. The 2000-series are market indices and benchmark rates: they exist only here, so
-- they quote and chart but can never be ordered (trading.instruments has no matching row, and
-- order submission resolves instruments against that table). Their drift/volatility are set to
-- behave like the thing they model - broad indices drift up with low vol, VIX mean-reverts
-- around a low level with very high vol, and the 10Y yield is modelled as a rate in percent.
INSERT INTO marketdata.instruments (symbol, display_name, initial_price, drift, volatility, rng_seed, is_active) VALUES
    ('AAPL', 'Apple Inc.', 150.25, 0.07, 0.25, 1001, TRUE),
    ('MSFT', 'Microsoft Corporation', 380.50, 0.08, 0.22, 1002, TRUE),
    ('GOOGL', 'Alphabet Inc.', 140.75, 0.06, 0.28, 1003, TRUE),
    ('TSLA', 'Tesla Inc.', 250.00, 0.05, 0.45, 1004, TRUE),
    ('BTCUSD', 'Bitcoin USD', 65000.00, 0.10, 0.65, 1005, TRUE),
    ('SPX', 'S&P 500', 4650.00, 0.08, 0.15, 2001, TRUE),
    ('NDX', 'NASDAQ 100', 13850.00, 0.10, 0.20, 2002, TRUE),
    ('DJI', 'Dow Jones Industrial Average', 34500.00, 0.06, 0.13, 2003, TRUE),
    ('RUT', 'Russell 2000', 1820.00, 0.05, 0.22, 2004, TRUE),
    ('VIX', 'CBOE Volatility Index', 13.50, 0.00, 0.85, 2005, TRUE),
    ('US10Y', '10-Year Treasury Yield', 4.25, 0.00, 0.18, 2006, TRUE)
ON CONFLICT (symbol) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    initial_price = EXCLUDED.initial_price,
    drift = EXCLUDED.drift,
    volatility = EXCLUDED.volatility,
    rng_seed = EXCLUDED.rng_seed,
    is_active = EXCLUDED.is_active;
