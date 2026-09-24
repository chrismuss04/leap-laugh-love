-- Trading Schema Seed Data
-- Simple, readable seed data for testing trading functionality
--
-- Fills are NOT written here. A filled order is seeded with its filled_at only, and
-- order-app's SeededFillService books its execution, cash settlement, position movement and
-- holding at startup, priced from market data at that moment. This file runs when the database
-- is created - before market-data-app has generated any price history - and the fill ledgers
-- are append-only, so a hard-coded price here could never match the simulated market, and the
-- gap showed up in portfolio history as the portfolio jumping in value on the fill.
--
-- Fill times must fall inside market data's generated history (the last 365 days), so they are
-- relative to when the database is seeded rather than fixed dates.
--
-- Every account is still funded when it opens, and rejected orders keep their (price-less)
-- rejected executions here, since those need no market data.

-- Insert trading instruments
INSERT INTO trading.instruments (symbol, instrument_name, asset_class, market, currency, is_tradable) VALUES
    ('AAPL', 'Apple Inc.', 'EQUITY', 'NASDAQ', 'USD', TRUE),
    ('MSFT', 'Microsoft Corporation', 'EQUITY', 'NASDAQ', 'USD', TRUE),
    ('GOOGL', 'Alphabet Inc.', 'EQUITY', 'NASDAQ', 'USD', TRUE),
    ('TSLA', 'Tesla Inc.', 'EQUITY', 'NASDAQ', 'USD', TRUE),
    ('BTC/USD', 'Bitcoin USD', 'CRYPTO', 'SPOT', 'USD', TRUE)
ON CONFLICT (symbol, market) DO UPDATE SET
    instrument_name = EXCLUDED.instrument_name,
    is_tradable = EXCLUDED.is_tradable;

-- Get client IDs and create accounts
-- Using a temp table approach or direct insert with subqueries
-- Each account opens before its first order. Alice's history starts 195 days before seeding, so
-- hers opens 200 days before; the rest open in 2025 and have no fills until the last day.
WITH client_map AS (
    SELECT client_id, email FROM iam.clients
)
INSERT INTO trading.accounts (client_id, account_number, status, base_currency, trading_enabled, created_at) VALUES
    ((SELECT client_id FROM client_map WHERE email = 'alice.johnson@leap.com'), 'ACC-001-01', 'ACTIVE', 'USD', TRUE, date_trunc('day', NOW()) - INTERVAL '200 days' + TIME '09:00'),
    ((SELECT client_id FROM client_map WHERE email = 'bob.smith@leap.com'), 'ACC-002-01', 'ACTIVE', 'USD', TRUE, '2025-04-07 09:00:00+00'),
    ((SELECT client_id FROM client_map WHERE email = 'carol.williams@leap.com'), 'ACC-003-01', 'ACTIVE', 'USD', TRUE, '2025-04-14 09:00:00+00'),
    ((SELECT client_id FROM client_map WHERE email = 'david.brown@leap.com'), 'ACC-004-01', 'PENDING', 'USD', FALSE, '2025-05-05 09:00:00+00'),
    ((SELECT client_id FROM client_map WHERE email = 'emma.davis@leap.com'), 'ACC-005-01', 'ACTIVE', 'USD', TRUE, '2025-05-12 09:00:00+00'),
    ((SELECT client_id FROM client_map WHERE email = 'frank.miller@leap.com'), 'ACC-006-01', 'ACTIVE', 'USD', TRUE, '2025-05-19 09:00:00+00'),
    ((SELECT client_id FROM client_map WHERE email = 'grace.wilson@leap.com'), 'ACC-007-01', 'BLOCKED', 'USD', FALSE, '2025-06-02 09:00:00+00'),
    ((SELECT client_id FROM client_map WHERE email = 'henry.taylor@leap.com'), 'ACC-008-01', 'ACTIVE', 'USD', TRUE, '2025-06-09 09:00:00+00'),
    ((SELECT client_id FROM client_map WHERE email = 'iris.anderson@leap.com'), 'ACC-009-01', 'ACTIVE', 'USD', TRUE, '2025-06-16 09:00:00+00'),
    ((SELECT client_id FROM client_map WHERE email = 'iris.anderson@leap.com'), 'ACC-009-02', 'ACTIVE', 'USD', TRUE, '2025-09-01 09:00:00+00'),
    ((SELECT client_id FROM client_map WHERE email = 'jack.thomas@leap.com'), 'ACC-010-01', 'ACTIVE', 'USD', TRUE, '2025-07-07 09:00:00+00')
ON CONFLICT (account_number) DO UPDATE SET
    status = EXCLUDED.status,
    trading_enabled = EXCLUDED.trading_enabled,
    created_at = EXCLUDED.created_at;

-- Insert initial cash deposits for each account, funded the moment it opens.
-- The cash ledger is append-only (no UPDATE), so re-runs leave existing rows alone.
INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, entry_type, amount, currency, created_at, description)
SELECT
    CASE a.account_number
        WHEN 'ACC-001-01' THEN 'dddddddd-dddd-dddd-dddd-eeeeeeee0001'::UUID
        WHEN 'ACC-002-01' THEN 'dddddddd-dddd-dddd-dddd-eeeeeeee0002'::UUID
        WHEN 'ACC-003-01' THEN 'dddddddd-dddd-dddd-dddd-eeeeeeee0003'::UUID
        WHEN 'ACC-004-01' THEN 'dddddddd-dddd-dddd-dddd-eeeeeeee0004'::UUID
        WHEN 'ACC-005-01' THEN 'dddddddd-dddd-dddd-dddd-eeeeeeee0005'::UUID
        WHEN 'ACC-006-01' THEN 'dddddddd-dddd-dddd-dddd-eeeeeeee0006'::UUID
        WHEN 'ACC-007-01' THEN 'dddddddd-dddd-dddd-dddd-eeeeeeee0007'::UUID
        WHEN 'ACC-008-01' THEN 'dddddddd-dddd-dddd-dddd-eeeeeeee0008'::UUID
        WHEN 'ACC-009-01' THEN 'dddddddd-dddd-dddd-dddd-eeeeeeee0009'::UUID
        WHEN 'ACC-009-02' THEN 'dddddddd-dddd-dddd-dddd-eeeeeeee0091'::UUID
        WHEN 'ACC-010-01' THEN 'dddddddd-dddd-dddd-dddd-eeeeeeee0010'::UUID
    END,
    a.account_id, 'DEPOSIT', cp.initial_deposit_amount, 'USD', a.created_at, 'Initial account funding'
FROM trading.accounts a
INNER JOIN iam.client_profile cp ON a.client_id = cp.client_id
WHERE a.account_number LIKE 'ACC-0%'
ON CONFLICT (cash_ledger_id) DO NOTHING;

-- Insert orders. Re-runs leave existing orders alone (DO NOTHING): once a fill has been booked
-- against an order, moving its filled_at would put the order and its ledgers out of step.
-- Order 1: Alice buys 40 AAPL (filled yesterday)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'AAPL'),
    'BUY', 40, 'FILLED',
    NOW() - INTERVAL '2 days' - INTERVAL '1 minute',
    NOW() - INTERVAL '2 days',
    NOW() - INTERVAL '1 day'
ON CONFLICT (order_id) DO NOTHING;

-- Orders 1a-1h: Alice's older history across the last ~6 months (mixed sides/statuses, two
-- same-day pairs) so the history day/month filters have data to narrow.
-- Order 1a: Alice buys 20 MSFT, 195 days back (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa11'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'MSFT'),
    'BUY', 20, 'FILLED',
    date_trunc('day', NOW()) - INTERVAL '195 days' + TIME '10:15',
    date_trunc('day', NOW()) - INTERVAL '195 days' + TIME '10:16',
    date_trunc('day', NOW()) - INTERVAL '195 days' + TIME '10:17'
ON CONFLICT (order_id) DO NOTHING;

-- Order 1b: Alice buys 10 GOOGL, same day as 1a (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa12'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'GOOGL'),
    'BUY', 10, 'FILLED',
    date_trunc('day', NOW()) - INTERVAL '195 days' + TIME '14:40',
    date_trunc('day', NOW()) - INTERVAL '195 days' + TIME '14:41',
    date_trunc('day', NOW()) - INTERVAL '195 days' + TIME '14:42'
ON CONFLICT (order_id) DO NOTHING;

-- Order 1c: Alice sells 20 MSFT, 120 days back (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa13'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'MSFT'),
    'SELL', 20, 'FILLED',
    date_trunc('day', NOW()) - INTERVAL '120 days' + TIME '16:05',
    date_trunc('day', NOW()) - INTERVAL '120 days' + TIME '16:06',
    date_trunc('day', NOW()) - INTERVAL '120 days' + TIME '16:07'
ON CONFLICT (order_id) DO NOTHING;

-- Order 1d: Alice tries to buy 500 BTC/USD, 90 days back (REJECTED - insufficient funds)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, rejected_at, rejection_reason)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa15'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'BTC/USD'),
    'BUY', 500, 'REJECTED',
    date_trunc('day', NOW()) - INTERVAL '90 days' + TIME '11:45',
    date_trunc('day', NOW()) - INTERVAL '90 days' + TIME '11:46',
    'Insufficient funds'
ON CONFLICT (order_id) DO NOTHING;

-- Order 1e: Alice buys 50 AAPL, 51 days back (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa16'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'AAPL'),
    'BUY', 50, 'FILLED',
    date_trunc('day', NOW()) - INTERVAL '51 days' + TIME '13:20',
    date_trunc('day', NOW()) - INTERVAL '51 days' + TIME '13:21',
    date_trunc('day', NOW()) - INTERVAL '51 days' + TIME '13:22'
ON CONFLICT (order_id) DO NOTHING;

-- Order 1f: Alice sells 25 AAPL, same day as 1e (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa17'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'AAPL'),
    'SELL', 25, 'FILLED',
    date_trunc('day', NOW()) - INTERVAL '51 days' + TIME '15:55',
    date_trunc('day', NOW()) - INTERVAL '51 days' + TIME '15:56',
    date_trunc('day', NOW()) - INTERVAL '51 days' + TIME '15:57'
ON CONFLICT (order_id) DO NOTHING;

-- Order 1g: Alice buys 30 GOOGL, 35 days back (accepted, not yet filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa18'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'GOOGL'),
    'BUY', 30, 'ACCEPTED',
    date_trunc('day', NOW()) - INTERVAL '35 days' + TIME '10:10',
    date_trunc('day', NOW()) - INTERVAL '35 days' + TIME '10:11'
ON CONFLICT (order_id) DO NOTHING;

-- Order 1h: Alice sells 10 GOOGL, 21 days back (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa19'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'GOOGL'),
    'SELL', 10, 'FILLED',
    date_trunc('day', NOW()) - INTERVAL '21 days' + TIME '12:00',
    date_trunc('day', NOW()) - INTERVAL '21 days' + TIME '12:01',
    date_trunc('day', NOW()) - INTERVAL '21 days' + TIME '12:02'
ON CONFLICT (order_id) DO NOTHING;

-- Order 2: Bob buys 50 MSFT (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-002-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'MSFT'),
    'BUY', 50, 'FILLED',
    NOW() - INTERVAL '1 day' - INTERVAL '1 minute',
    NOW() - INTERVAL '1 day',
    NOW() - INTERVAL '12 hours'
ON CONFLICT (order_id) DO NOTHING;

-- Order 3: Carol buys 25 GOOGL (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-003-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'GOOGL'),
    'BUY', 25, 'FILLED',
    NOW() - INTERVAL '6 hours' - INTERVAL '1 minute',
    NOW() - INTERVAL '6 hours',
    NOW() - INTERVAL '4 hours'
ON CONFLICT (order_id) DO NOTHING;

-- Order 4: Henry tries to buy 1000000 TSLA (REJECTED - insufficient funds)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, rejected_at, rejection_reason)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-008-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'TSLA'),
    'BUY', 1000000, 'REJECTED',
    NOW() - INTERVAL '3 hours' - INTERVAL '1 minute',
    NOW() - INTERVAL '3 hours',
    'Insufficient funds'
ON CONFLICT (order_id) DO NOTHING;

-- Rejected orders' executions (no fill, so no price needed). Filled orders get theirs from
-- SeededFillService. Executions are append-only, so DO NOTHING keeps re-runs safe.
-- Execution 1d: Alice's BTC/USD order REJECTED
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
SELECT 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb15'::UUID, o.order_id, NULL, NULL, 'REJECTED', o.rejected_at,
       'Insufficient funds - order rejected'
FROM trading.orders o WHERE o.order_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa15'::UUID
ON CONFLICT (execution_id) DO NOTHING;

-- Execution 4: Henry's TSLA order REJECTED
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
SELECT 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb4'::UUID, o.order_id, NULL, NULL, 'REJECTED', o.rejected_at,
       'Insufficient funds - order rejected'
FROM trading.orders o WHERE o.order_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4'::UUID
ON CONFLICT (execution_id) DO NOTHING;
