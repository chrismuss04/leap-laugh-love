-- Trading Schema Seed Data
-- Simple, readable seed data for testing trading functionality

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
WITH client_map AS (
    SELECT client_id, email FROM iam.clients
)
INSERT INTO trading.accounts (client_id, account_number, status, base_currency, trading_enabled) VALUES
    ((SELECT client_id FROM client_map WHERE email = 'alice.johnson@leap.com'), 'ACC-001-01', 'ACTIVE', 'USD', TRUE),
    ((SELECT client_id FROM client_map WHERE email = 'bob.smith@leap.com'), 'ACC-002-01', 'ACTIVE', 'USD', TRUE),
    ((SELECT client_id FROM client_map WHERE email = 'carol.williams@leap.com'), 'ACC-003-01', 'ACTIVE', 'USD', TRUE),
    ((SELECT client_id FROM client_map WHERE email = 'david.brown@leap.com'), 'ACC-004-01', 'PENDING', 'USD', FALSE),
    ((SELECT client_id FROM client_map WHERE email = 'emma.davis@leap.com'), 'ACC-005-01', 'ACTIVE', 'USD', TRUE),
    ((SELECT client_id FROM client_map WHERE email = 'frank.miller@leap.com'), 'ACC-006-01', 'ACTIVE', 'USD', TRUE),
    ((SELECT client_id FROM client_map WHERE email = 'grace.wilson@leap.com'), 'ACC-007-01', 'BLOCKED', 'USD', FALSE),
    ((SELECT client_id FROM client_map WHERE email = 'henry.taylor@leap.com'), 'ACC-008-01', 'ACTIVE', 'USD', TRUE),
    ((SELECT client_id FROM client_map WHERE email = 'iris.anderson@leap.com'), 'ACC-009-01', 'ACTIVE', 'USD', TRUE),
    ((SELECT client_id FROM client_map WHERE email = 'iris.anderson@leap.com'), 'ACC-009-02', 'ACTIVE', 'USD', TRUE),
    ((SELECT client_id FROM client_map WHERE email = 'jack.thomas@leap.com'), 'ACC-010-01', 'ACTIVE', 'USD', TRUE)
ON CONFLICT (account_number) DO UPDATE SET
    status = EXCLUDED.status,
    trading_enabled = EXCLUDED.trading_enabled;

-- Insert initial cash deposits for each account
INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, entry_type, amount, currency, description) 
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
    a.account_id, 'DEPOSIT', cp.initial_deposit_amount, 'USD', 'Initial account funding'
FROM trading.accounts a
INNER JOIN iam.client_profile cp ON a.client_id = cp.client_id
ON CONFLICT (cash_ledger_id) DO UPDATE SET
    amount = EXCLUDED.amount,
    description = EXCLUDED.description;

-- Insert orders: 4 total (3 successful, 1 rejected)
-- Order 1: Alice buys 100 AAPL (will be filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'AAPL'),
    'BUY', 100, 'FILLED',
    NOW() - INTERVAL '2 days',
    NOW() - INTERVAL '1 day'
ON CONFLICT (order_id) DO UPDATE SET
    quantity = EXCLUDED.quantity,
    status = EXCLUDED.status,
    accepted_at = EXCLUDED.accepted_at,
    filled_at = EXCLUDED.filled_at;

-- Orders 1a-1h: Alice's older history on fixed dates (2025-2026, mixed sides/statuses,
-- two same-day pairs) so the history day/month/year filters have data to narrow.
-- Order 1a: Alice buys 20 MSFT on 2025-03-12 (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa11'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'MSFT'),
    'BUY', 20, 'FILLED',
    '2025-03-12 10:15:00+00'::TIMESTAMPTZ,
    '2025-03-12 10:16:00+00'::TIMESTAMPTZ,
    '2025-03-12 10:17:00+00'::TIMESTAMPTZ
ON CONFLICT (order_id) DO NOTHING;

-- Order 1b: Alice buys 10 GOOGL on 2025-03-12, same day as 1a (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa12'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'GOOGL'),
    'BUY', 10, 'FILLED',
    '2025-03-12 14:40:00+00'::TIMESTAMPTZ,
    '2025-03-12 14:41:00+00'::TIMESTAMPTZ,
    '2025-03-12 14:42:00+00'::TIMESTAMPTZ
ON CONFLICT (order_id) DO NOTHING;

-- Order 1c: Alice sells 20 MSFT on 2025-11-20 (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa13'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'MSFT'),
    'SELL', 20, 'FILLED',
    '2025-11-20 16:05:00+00'::TIMESTAMPTZ,
    '2025-11-20 16:06:00+00'::TIMESTAMPTZ,
    '2025-11-20 16:07:00+00'::TIMESTAMPTZ
ON CONFLICT (order_id) DO NOTHING;

-- Order 1d: Alice tries to buy 500 BTC/USD on 2026-01-28 (REJECTED - insufficient funds)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, rejected_at, rejection_reason)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa15'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'BTC/USD'),
    'BUY', 500, 'REJECTED',
    '2026-01-28 11:45:00+00'::TIMESTAMPTZ,
    '2026-01-28 11:46:00+00'::TIMESTAMPTZ,
    'Insufficient funds'
ON CONFLICT (order_id) DO NOTHING;

-- Order 1e: Alice buys 50 AAPL on 2026-08-03 (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa16'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'AAPL'),
    'BUY', 50, 'FILLED',
    '2026-08-03 13:20:00+00'::TIMESTAMPTZ,
    '2026-08-03 13:21:00+00'::TIMESTAMPTZ,
    '2026-08-03 13:22:00+00'::TIMESTAMPTZ
ON CONFLICT (order_id) DO NOTHING;

-- Order 1f: Alice sells 25 AAPL on 2026-08-03, same day as 1e (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa17'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'AAPL'),
    'SELL', 25, 'FILLED',
    '2026-08-03 15:55:00+00'::TIMESTAMPTZ,
    '2026-08-03 15:56:00+00'::TIMESTAMPTZ,
    '2026-08-03 15:57:00+00'::TIMESTAMPTZ
ON CONFLICT (order_id) DO NOTHING;

-- Order 1g: Alice buys 30 GOOGL on 2026-08-19 (accepted, not yet filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa18'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'GOOGL'),
    'BUY', 30, 'ACCEPTED',
    '2026-08-19 10:10:00+00'::TIMESTAMPTZ,
    '2026-08-19 10:11:00+00'::TIMESTAMPTZ
ON CONFLICT (order_id) DO NOTHING;

-- Order 1h: Alice sells 10 GOOGL on 2026-09-02 (filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa19'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'GOOGL'),
    'SELL', 10, 'FILLED',
    '2026-09-02 12:00:00+00'::TIMESTAMPTZ,
    '2026-09-02 12:01:00+00'::TIMESTAMPTZ,
    '2026-09-02 12:02:00+00'::TIMESTAMPTZ
ON CONFLICT (order_id) DO NOTHING;

-- Order 2: Bob buys 50 MSFT (will be filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-002-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'MSFT'),
    'BUY', 50, 'FILLED',
    NOW() - INTERVAL '1 day',
    NOW() - INTERVAL '12 hours'
ON CONFLICT (order_id) DO UPDATE SET
    quantity = EXCLUDED.quantity,
    status = EXCLUDED.status,
    accepted_at = EXCLUDED.accepted_at,
    filled_at = EXCLUDED.filled_at;

-- Order 3: Carol buys 25 GOOGL (will be filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-003-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'GOOGL'),
    'BUY', 25, 'FILLED',
    NOW() - INTERVAL '6 hours',
    NOW() - INTERVAL '4 hours'
ON CONFLICT (order_id) DO UPDATE SET
    quantity = EXCLUDED.quantity,
    status = EXCLUDED.status,
    accepted_at = EXCLUDED.accepted_at,
    filled_at = EXCLUDED.filled_at;

-- Order 4: Henry tries to buy 1000000 TSLA (will be REJECTED - insufficient funds)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, rejected_at, rejection_reason)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-008-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'TSLA'),
    'BUY', 1000000, 'REJECTED',
    NOW() - INTERVAL '3 hours',
    'Insufficient funds'
ON CONFLICT (order_id) DO UPDATE SET
    quantity = EXCLUDED.quantity,
    status = EXCLUDED.status,
    rejected_at = EXCLUDED.rejected_at,
    rejection_reason = EXCLUDED.rejection_reason;

-- Insert executions for the filled orders
-- Execution 1: Alice's AAPL order filled at $150.25
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::UUID,
     100, 150.25, 'FILLED', 'Executed at market price')
ON CONFLICT (execution_id) DO UPDATE SET
    fill_quantity = EXCLUDED.fill_quantity,
    fill_price = EXCLUDED.fill_price,
    status = EXCLUDED.status,
    reason = EXCLUDED.reason;

-- Executions 1a-1h: Alice's older orders (1g is only accepted, so it has no execution yet).
-- Executions are append-only, so DO NOTHING keeps re-runs safe.
-- Execution 1a: Alice's MSFT buy filled at $372.10
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb11'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa11'::UUID,
     20, 372.10, 'FILLED', '2025-03-12 10:17:00+00'::TIMESTAMPTZ, 'Executed at market price')
ON CONFLICT (execution_id) DO NOTHING;

-- Execution 1b: Alice's GOOGL buy filled at $165.40
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb12'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa12'::UUID,
     10, 165.40, 'FILLED', '2025-03-12 14:42:00+00'::TIMESTAMPTZ, 'Executed at market price')
ON CONFLICT (execution_id) DO NOTHING;

-- Execution 1c: Alice's MSFT sell filled at $391.85
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb13'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa13'::UUID,
     20, 391.85, 'FILLED', '2025-11-20 16:07:00+00'::TIMESTAMPTZ, 'Executed at market price')
ON CONFLICT (execution_id) DO NOTHING;

-- Execution 1d: Alice's BTC/USD order REJECTED - recorded as failed execution
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb15'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa15'::UUID,
     NULL, NULL, 'REJECTED', '2026-01-28 11:46:00+00'::TIMESTAMPTZ, 'Insufficient funds - order rejected')
ON CONFLICT (execution_id) DO NOTHING;

-- Execution 1e: Alice's AAPL buy filled at $187.60
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb16'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa16'::UUID,
     50, 187.60, 'FILLED', '2026-08-03 13:22:00+00'::TIMESTAMPTZ, 'Executed at market price')
ON CONFLICT (execution_id) DO NOTHING;

-- Execution 1f: Alice's AAPL sell filled at $189.15
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb17'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa17'::UUID,
     25, 189.15, 'FILLED', '2026-08-03 15:57:00+00'::TIMESTAMPTZ, 'Executed at market price')
ON CONFLICT (execution_id) DO NOTHING;

-- Execution 1h: Alice's GOOGL sell filled at $171.25
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb19'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa19'::UUID,
     10, 171.25, 'FILLED', '2026-09-02 12:02:00+00'::TIMESTAMPTZ, 'Executed at market price')
ON CONFLICT (execution_id) DO NOTHING;

-- Execution 2: Bob's MSFT order filled at $380.50
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::UUID,
     50, 380.50, 'FILLED', 'Executed at market price')
ON CONFLICT (execution_id) DO UPDATE SET
    fill_quantity = EXCLUDED.fill_quantity,
    fill_price = EXCLUDED.fill_price,
    status = EXCLUDED.status,
    reason = EXCLUDED.reason;

-- Execution 3: Carol's GOOGL order filled at $140.75
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb3'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::UUID,
     25, 140.75, 'FILLED', 'Executed at market price')
ON CONFLICT (execution_id) DO UPDATE SET
    fill_quantity = EXCLUDED.fill_quantity,
    fill_price = EXCLUDED.fill_price,
    status = EXCLUDED.status,
    reason = EXCLUDED.reason;

-- Execution 4: Henry's TSLA order REJECTED - recorded as failed execution
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb4'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4'::UUID,
     NULL, NULL, 'REJECTED', 'Insufficient funds - order rejected')
ON CONFLICT (execution_id) DO UPDATE SET
    fill_quantity = EXCLUDED.fill_quantity,
    fill_price = EXCLUDED.fill_price,
    status = EXCLUDED.status,
    reason = EXCLUDED.reason;

-- Record cash ledger entries for successful executions (buy settlements)
-- Alice's AAPL purchase settlement
INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, order_id, execution_id, entry_type, amount, currency, description)
VALUES 
    ('cccccccc-cccc-cccc-cccc-ccccccccccc1'::UUID,
     (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::UUID,
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1'::UUID,
     'BUY_SETTLEMENT',
     -15025.00,
     'USD',
     'Buy settlement: AAPL 100 @ $150.25')
ON CONFLICT (cash_ledger_id) DO UPDATE SET
    entry_type = EXCLUDED.entry_type,
    amount = EXCLUDED.amount,
    description = EXCLUDED.description;

-- Bob's MSFT purchase settlement
INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, order_id, execution_id, entry_type, amount, currency, description)
VALUES 
    ('cccccccc-cccc-cccc-cccc-ccccccccccc2'::UUID,
     (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-002-01'),
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::UUID,
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2'::UUID,
     'BUY_SETTLEMENT',
     -19025.00,
     'USD',
     'Buy settlement: MSFT 50 @ $380.50')
ON CONFLICT (cash_ledger_id) DO UPDATE SET
    entry_type = EXCLUDED.entry_type,
    amount = EXCLUDED.amount,
    description = EXCLUDED.description;

-- Carol's GOOGL purchase settlement
INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, order_id, execution_id, entry_type, amount, currency, description)
VALUES 
    ('cccccccc-cccc-cccc-cccc-ccccccccccc3'::UUID,
     (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-003-01'),
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::UUID,
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb3'::UUID,
     'BUY_SETTLEMENT',
     -3518.75,
     'USD',
     'Buy settlement: GOOGL 25 @ $140.75')
ON CONFLICT (cash_ledger_id) DO UPDATE SET
    entry_type = EXCLUDED.entry_type,
    amount = EXCLUDED.amount,
    description = EXCLUDED.description;

-- Record position movements for successful executions
-- Alice's position in AAPL
INSERT INTO trading.position_movements (movement_id, account_id, instrument_id, order_id, execution_id, movement_type, quantity_delta, cost_delta)
VALUES 
    ('dddddddd-dddd-dddd-dddd-ddddddddddd1'::UUID,
     (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
     (SELECT instrument_id FROM trading.instruments WHERE symbol = 'AAPL'),
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::UUID,
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1'::UUID,
     'BUY_FILL',
     100,
     15025.00)
ON CONFLICT (movement_id) DO UPDATE SET
    movement_type = EXCLUDED.movement_type,
    quantity_delta = EXCLUDED.quantity_delta,
    cost_delta = EXCLUDED.cost_delta;

-- Bob's position in MSFT
INSERT INTO trading.position_movements (movement_id, account_id, instrument_id, order_id, execution_id, movement_type, quantity_delta, cost_delta)
VALUES 
    ('dddddddd-dddd-dddd-dddd-ddddddddddd2'::UUID,
     (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-002-01'),
     (SELECT instrument_id FROM trading.instruments WHERE symbol = 'MSFT'),
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::UUID,
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2'::UUID,
     'BUY_FILL',
     50,
     19025.00)
ON CONFLICT (movement_id) DO UPDATE SET
    movement_type = EXCLUDED.movement_type,
    quantity_delta = EXCLUDED.quantity_delta,
    cost_delta = EXCLUDED.cost_delta;

-- Carol's position in GOOGL
INSERT INTO trading.position_movements (movement_id, account_id, instrument_id, order_id, execution_id, movement_type, quantity_delta, cost_delta)
VALUES 
    ('dddddddd-dddd-dddd-dddd-ddddddddddd3'::UUID,
     (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-003-01'),
     (SELECT instrument_id FROM trading.instruments WHERE symbol = 'GOOGL'),
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::UUID,
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb3'::UUID,
     'BUY_FILL',
     25,
     3518.75)
ON CONFLICT (movement_id) DO UPDATE SET
    movement_type = EXCLUDED.movement_type,
    quantity_delta = EXCLUDED.quantity_delta,
    cost_delta = EXCLUDED.cost_delta;

-- Update positions (aggregate current holdings)
INSERT INTO trading.positions (account_id, instrument_id, quantity, avg_cost)
SELECT 
    pm.account_id, pm.instrument_id,
    COALESCE(SUM(pm.quantity_delta), 0),
    CASE WHEN COALESCE(SUM(pm.quantity_delta), 0) > 0 
         THEN COALESCE(SUM(pm.cost_delta), 0) / NULLIF(SUM(pm.quantity_delta), 0) 
         ELSE 0 
    END
FROM trading.position_movements pm
GROUP BY pm.account_id, pm.instrument_id
ON CONFLICT (account_id, instrument_id) DO UPDATE SET
    quantity = EXCLUDED.quantity,
    avg_cost = EXCLUDED.avg_cost,
    updated_at = NOW();
