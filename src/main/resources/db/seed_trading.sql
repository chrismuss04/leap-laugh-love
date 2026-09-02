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
        WHEN 'ACC-009-02' THEN 'dddddddd-dddd-dddd-dddd-eeeeeeee00091'::UUID
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
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb1'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::UUID,
     100, 150.25, 'FILLED', 'Executed at market price')
ON CONFLICT (execution_id) DO UPDATE SET
    fill_quantity = EXCLUDED.fill_quantity,
    fill_price = EXCLUDED.fill_price,
    status = EXCLUDED.status,
    reason = EXCLUDED.reason;

-- Execution 2: Bob's MSFT order filled at $380.50
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, reason)
VALUES 
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb2'::UUID,
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
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb3'::UUID,
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
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb4'::UUID,
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
    ('cccccccc-cccc-cccc-cccc-cccccccccccc1'::UUID,
     (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::UUID,
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb1'::UUID,
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
    ('cccccccc-cccc-cccc-cccc-cccccccccccc2'::UUID,
     (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-002-01'),
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::UUID,
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb2'::UUID,
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
    ('cccccccc-cccc-cccc-cccc-cccccccccccc3'::UUID,
     (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-003-01'),
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::UUID,
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb3'::UUID,
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
    ('dddddddd-dddd-dddd-dddd-dddddddddddd1'::UUID,
     (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
     (SELECT instrument_id FROM trading.instruments WHERE symbol = 'AAPL'),
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::UUID,
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb1'::UUID,
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
    ('dddddddd-dddd-dddd-dddd-dddddddddddd2'::UUID,
     (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-002-01'),
     (SELECT instrument_id FROM trading.instruments WHERE symbol = 'MSFT'),
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::UUID,
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb2'::UUID,
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
    ('dddddddd-dddd-dddd-dddd-dddddddddddd3'::UUID,
     (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-003-01'),
     (SELECT instrument_id FROM trading.instruments WHERE symbol = 'GOOGL'),
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::UUID,
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb3'::UUID,
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
