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
INSERT INTO trading.cash_ledger (account_id, entry_type, amount, currency, description) 
SELECT a.account_id, 'DEPOSIT', cp.initial_deposit_amount, 'USD', 'Initial account funding'
FROM trading.accounts a
INNER JOIN iam.client_profile cp ON a.client_id = cp.client_id
ON CONFLICT DO NOTHING;

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

-- Order 2: Bob buys 50 MSFT (will be filled)
INSERT INTO trading.orders (account_id, instrument_id, side, quantity, status, accepted_at, filled_at)
SELECT 
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-002-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'MSFT'),
    'BUY', 50, 'FILLED',
    NOW() - INTERVAL '1 day',
    NOW() - INTERVAL '12 hours'
ON CONFLICT DO NOTHING;

-- Order 3: Carol buys 25 GOOGL (will be filled)
INSERT INTO trading.orders (account_id, instrument_id, side, quantity, status, accepted_at, filled_at)
SELECT 
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-003-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'GOOGL'),
    'BUY', 25, 'FILLED',
    NOW() - INTERVAL '6 hours',
    NOW() - INTERVAL '4 hours'
ON CONFLICT DO NOTHING;

-- Order 4: Henry tries to buy 1000000 TSLA (will be REJECTED - insufficient funds)
INSERT INTO trading.orders (account_id, instrument_id, side, quantity, status, rejected_at, rejection_reason)
SELECT 
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-008-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'TSLA'),
    'BUY', 1000000, 'REJECTED',
    NOW() - INTERVAL '3 hours',
    'Insufficient funds'
ON CONFLICT DO NOTHING;

-- Insert executions for the filled orders
-- Execution 1: Alice's AAPL order filled at $150.25
INSERT INTO trading.executions (order_id, fill_quantity, fill_price, status, reason)
SELECT 
    (SELECT order_id FROM trading.orders o 
     INNER JOIN trading.accounts a ON o.account_id = a.account_id
     WHERE a.account_number = 'ACC-001-01' AND o.side = 'BUY' AND o.status = 'FILLED'),
    100, 150.25, 'FILLED', 'Executed at market price'
ON CONFLICT DO NOTHING;

-- Execution 2: Bob's MSFT order filled at $380.50
INSERT INTO trading.executions (order_id, fill_quantity, fill_price, status, reason)
SELECT 
    (SELECT order_id FROM trading.orders o 
     INNER JOIN trading.accounts a ON o.account_id = a.account_id
     WHERE a.account_number = 'ACC-002-01' AND o.side = 'BUY' AND o.status = 'FILLED'),
    50, 380.50, 'FILLED', 'Executed at market price'
ON CONFLICT DO NOTHING;

-- Execution 3: Carol's GOOGL order filled at $140.75
INSERT INTO trading.executions (order_id, fill_quantity, fill_price, status, reason)
SELECT 
    (SELECT order_id FROM trading.orders o 
     INNER JOIN trading.accounts a ON o.account_id = a.account_id
     WHERE a.account_number = 'ACC-003-01' AND o.side = 'BUY' AND o.status = 'FILLED'),
    25, 140.75, 'FILLED', 'Executed at market price'
ON CONFLICT DO NOTHING;

-- Execution 4: Henry's TSLA order REJECTED - recorded as failed execution
INSERT INTO trading.executions (order_id, fill_quantity, fill_price, status, reason)
SELECT 
    (SELECT order_id FROM trading.orders o 
     INNER JOIN trading.accounts a ON o.account_id = a.account_id
     WHERE a.account_number = 'ACC-008-01' AND o.side = 'BUY' AND o.status = 'REJECTED'),
    NULL, NULL, 'REJECTED', 'Insufficient funds - order rejected'
ON CONFLICT DO NOTHING;

-- Record cash ledger entries for successful executions (buy settlements)
-- Alice's AAPL purchase settlement
INSERT INTO trading.cash_ledger (account_id, order_id, execution_id, entry_type, amount, currency, description)
SELECT 
    a.account_id, o.order_id, e.execution_id, 'BUY_SETTLEMENT', 
    -(e.fill_quantity * e.fill_price), 'USD',
    'Buy settlement: ' || i.symbol || ' ' || e.fill_quantity::TEXT || ' @ $' || e.fill_price::TEXT
FROM trading.executions e
INNER JOIN trading.orders o ON e.order_id = o.order_id
INNER JOIN trading.accounts a ON o.account_id = a.account_id
INNER JOIN trading.instruments i ON o.instrument_id = i.instrument_id
WHERE a.account_number = 'ACC-001-01' AND e.status = 'FILLED'
ON CONFLICT DO NOTHING;

-- Bob's MSFT purchase settlement
INSERT INTO trading.cash_ledger (account_id, order_id, execution_id, entry_type, amount, currency, description)
SELECT 
    a.account_id, o.order_id, e.execution_id, 'BUY_SETTLEMENT', 
    -(e.fill_quantity * e.fill_price), 'USD',
    'Buy settlement: ' || i.symbol || ' ' || e.fill_quantity::TEXT || ' @ $' || e.fill_price::TEXT
FROM trading.executions e
INNER JOIN trading.orders o ON e.order_id = o.order_id
INNER JOIN trading.accounts a ON o.account_id = a.account_id
INNER JOIN trading.instruments i ON o.instrument_id = i.instrument_id
WHERE a.account_number = 'ACC-002-01' AND e.status = 'FILLED'
ON CONFLICT DO NOTHING;

-- Carol's GOOGL purchase settlement
INSERT INTO trading.cash_ledger (account_id, order_id, execution_id, entry_type, amount, currency, description)
SELECT 
    a.account_id, o.order_id, e.execution_id, 'BUY_SETTLEMENT', 
    -(e.fill_quantity * e.fill_price), 'USD',
    'Buy settlement: ' || i.symbol || ' ' || e.fill_quantity::TEXT || ' @ $' || e.fill_price::TEXT
FROM trading.executions e
INNER JOIN trading.orders o ON e.order_id = o.order_id
INNER JOIN trading.accounts a ON o.account_id = a.account_id
INNER JOIN trading.instruments i ON o.instrument_id = i.instrument_id
WHERE a.account_number = 'ACC-003-01' AND e.status = 'FILLED'
ON CONFLICT DO NOTHING;

-- Record position movements for successful executions
-- Alice's position in AAPL
INSERT INTO trading.position_movements (account_id, instrument_id, order_id, execution_id, movement_type, quantity_delta, cost_delta)
SELECT 
    a.account_id, o.instrument_id, o.order_id, e.execution_id, 'BUY_FILL',
    e.fill_quantity, (e.fill_quantity * e.fill_price)
FROM trading.executions e
INNER JOIN trading.orders o ON e.order_id = o.order_id
INNER JOIN trading.accounts a ON o.account_id = a.account_id
WHERE a.account_number = 'ACC-001-01' AND e.status = 'FILLED'
ON CONFLICT DO NOTHING;

-- Bob's position in MSFT
INSERT INTO trading.position_movements (account_id, instrument_id, order_id, execution_id, movement_type, quantity_delta, cost_delta)
SELECT 
    a.account_id, o.instrument_id, o.order_id, e.execution_id, 'BUY_FILL',
    e.fill_quantity, (e.fill_quantity * e.fill_price)
FROM trading.executions e
INNER JOIN trading.orders o ON e.order_id = o.order_id
INNER JOIN trading.accounts a ON o.account_id = a.account_id
WHERE a.account_number = 'ACC-002-01' AND e.status = 'FILLED'
ON CONFLICT DO NOTHING;

-- Carol's position in GOOGL
INSERT INTO trading.position_movements (account_id, instrument_id, order_id, execution_id, movement_type, quantity_delta, cost_delta)
SELECT 
    a.account_id, o.instrument_id, o.order_id, e.execution_id, 'BUY_FILL',
    e.fill_quantity, (e.fill_quantity * e.fill_price)
FROM trading.executions e
INNER JOIN trading.orders o ON e.order_id = o.order_id
INNER JOIN trading.accounts a ON o.account_id = a.account_id
WHERE a.account_number = 'ACC-003-01' AND e.status = 'FILLED'
ON CONFLICT DO NOTHING;

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
