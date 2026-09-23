-- Trading Schema Seed Data
-- Simple, readable seed data for testing trading functionality
--
-- Keep the ledgers consistent with the order history, the way order submission does for real
-- trades: every filled execution has a cash settlement and a position movement stamped with
-- its execution time, every account is funded when it opens, and trading.positions is
-- derived from the movements. Portfolio history is rebuilt by walking these ledgers back in
-- time, so a row stamped with the seeding time instead of when it "happened" shows up as the
-- whole portfolio appearing on the day the database was created.

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
-- Each account opens before its first order. Alice's history starts 2025-03-12, so hers opens
-- at the start of that month; the rest open in early 2025.
WITH client_map AS (
    SELECT client_id, email FROM iam.clients
)
INSERT INTO trading.accounts (client_id, account_number, status, base_currency, trading_enabled, created_at) VALUES
    ((SELECT client_id FROM client_map WHERE email = 'alice.johnson@leap.com'), 'ACC-001-01', 'ACTIVE', 'USD', TRUE, '2025-03-01 09:00:00+00'),
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

-- Insert orders: 4 total (3 successful, 1 rejected)
-- Order 1: Alice buys 100 AAPL (will be filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-001-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'AAPL'),
    'BUY', 100, 'FILLED',
    NOW() - INTERVAL '2 days' - INTERVAL '1 minute',
    NOW() - INTERVAL '2 days',
    NOW() - INTERVAL '1 day'
ON CONFLICT (order_id) DO UPDATE SET
    quantity = EXCLUDED.quantity,
    status = EXCLUDED.status,
    submitted_at = EXCLUDED.submitted_at,
    accepted_at = EXCLUDED.accepted_at,
    filled_at = EXCLUDED.filled_at;

-- Order 2: Bob buys 50 MSFT (will be filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-002-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'MSFT'),
    'BUY', 50, 'FILLED',
    NOW() - INTERVAL '1 day' - INTERVAL '1 minute',
    NOW() - INTERVAL '1 day',
    NOW() - INTERVAL '12 hours'
ON CONFLICT (order_id) DO UPDATE SET
    quantity = EXCLUDED.quantity,
    status = EXCLUDED.status,
    submitted_at = EXCLUDED.submitted_at,
    accepted_at = EXCLUDED.accepted_at,
    filled_at = EXCLUDED.filled_at;

-- Order 3: Carol buys 25 GOOGL (will be filled)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at, filled_at)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-003-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'GOOGL'),
    'BUY', 25, 'FILLED',
    NOW() - INTERVAL '6 hours' - INTERVAL '1 minute',
    NOW() - INTERVAL '6 hours',
    NOW() - INTERVAL '4 hours'
ON CONFLICT (order_id) DO UPDATE SET
    quantity = EXCLUDED.quantity,
    status = EXCLUDED.status,
    submitted_at = EXCLUDED.submitted_at,
    accepted_at = EXCLUDED.accepted_at,
    filled_at = EXCLUDED.filled_at;

-- Order 4: Henry tries to buy 1000000 TSLA (will be REJECTED - insufficient funds)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, rejected_at, rejection_reason)
SELECT
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4'::UUID,
    (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-008-01'),
    (SELECT instrument_id FROM trading.instruments WHERE symbol = 'TSLA'),
    'BUY', 1000000, 'REJECTED',
    NOW() - INTERVAL '3 hours' - INTERVAL '1 minute',
    NOW() - INTERVAL '3 hours',
    'Insufficient funds'
ON CONFLICT (order_id) DO UPDATE SET
    quantity = EXCLUDED.quantity,
    status = EXCLUDED.status,
    submitted_at = EXCLUDED.submitted_at,
    rejected_at = EXCLUDED.rejected_at,
    rejection_reason = EXCLUDED.rejection_reason;

-- Insert executions for the filled orders
-- Execution 1: Alice's AAPL order filled at $150.25
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::UUID,
     100, 150.25, 'FILLED', NOW() - INTERVAL '1 day', 'Executed at market price')
ON CONFLICT (execution_id) DO NOTHING;

-- Execution 2: Bob's MSFT order filled at $380.50
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::UUID,
     50, 380.50, 'FILLED', NOW() - INTERVAL '12 hours', 'Executed at market price')
ON CONFLICT (execution_id) DO NOTHING;

-- Execution 3: Carol's GOOGL order filled at $140.75
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb3'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::UUID,
     25, 140.75, 'FILLED', NOW() - INTERVAL '4 hours', 'Executed at market price')
ON CONFLICT (execution_id) DO NOTHING;

-- Execution 4: Henry's TSLA order REJECTED - recorded as failed execution
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb4'::UUID,
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4'::UUID,
     NULL, NULL, 'REJECTED', NOW() - INTERVAL '3 hours', 'Insufficient funds - order rejected')
ON CONFLICT (execution_id) DO NOTHING;

-- Cash settlements for every filled seed execution, at its execution time - what
-- OrderSubmissionService writes for a real fill: a BUY debits price x quantity, a SELL credits it.
-- Ids are derived from the execution id (cccccccc-...), so re-runs are no-ops.
INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, order_id, execution_id, entry_type, amount, currency, created_at, description)
SELECT
    ('cccccccc-' || substr(e.execution_id::text, 10))::UUID,
    o.account_id,
    o.order_id,
    e.execution_id,
    CASE o.side WHEN 'BUY' THEN 'BUY_SETTLEMENT' ELSE 'SELL_SETTLEMENT' END,
    ROUND(e.fill_price * e.fill_quantity, 2) * CASE o.side WHEN 'BUY' THEN -1 ELSE 1 END,
    a.base_currency,
    e.executed_at,
    format('%s settlement: %s %s @ $%s',
           CASE o.side WHEN 'BUY' THEN 'Buy' ELSE 'Sell' END, i.symbol, e.fill_quantity, e.fill_price)
FROM trading.executions e
JOIN trading.orders o ON o.order_id = e.order_id
JOIN trading.accounts a ON a.account_id = o.account_id
JOIN trading.instruments i ON i.instrument_id = o.instrument_id
WHERE e.status = 'FILLED'
  AND e.execution_id::text LIKE 'bbbbbbbb-%'
ON CONFLICT (cash_ledger_id) DO NOTHING;

-- Position movements for the same fills (dddddddd-... ids): +quantity and +cost for a BUY_FILL,
-- -quantity and -proceeds for a SELL_FILL, again matching OrderSubmissionService.
INSERT INTO trading.position_movements (movement_id, account_id, instrument_id, order_id, execution_id, movement_type, quantity_delta, cost_delta, created_at)
SELECT
    ('dddddddd-' || substr(e.execution_id::text, 10))::UUID,
    o.account_id,
    o.instrument_id,
    o.order_id,
    e.execution_id,
    CASE o.side WHEN 'BUY' THEN 'BUY_FILL' ELSE 'SELL_FILL' END,
    e.fill_quantity * CASE o.side WHEN 'BUY' THEN 1 ELSE -1 END,
    ROUND(e.fill_price * e.fill_quantity, 2) * CASE o.side WHEN 'BUY' THEN 1 ELSE -1 END,
    e.executed_at
FROM trading.executions e
JOIN trading.orders o ON o.order_id = e.order_id
WHERE e.status = 'FILLED'
  AND e.execution_id::text LIKE 'bbbbbbbb-%'
ON CONFLICT (movement_id) DO NOTHING;

-- Current holdings, replayed from the movements in time order with the same rules order
-- submission applies: a buy re-averages the cost, a sell only reduces the quantity (floored at
-- zero), and a position that goes flat resets its average cost. A plain SUM(cost_delta) would
-- subtract sale proceeds from the cost basis and get the average wrong after any sell.
DO $$
DECLARE
    pos RECORD;
    mv RECORD;
    qty BIGINT;
    avg_cost NUMERIC(18,6);
    last_at TIMESTAMPTZ;
BEGIN
    FOR pos IN SELECT DISTINCT account_id, instrument_id FROM trading.position_movements LOOP
        qty := 0;
        avg_cost := 0;
        FOR mv IN
            SELECT quantity_delta, cost_delta, created_at
            FROM trading.position_movements
            WHERE account_id = pos.account_id AND instrument_id = pos.instrument_id
            ORDER BY created_at, movement_id
        LOOP
            IF mv.quantity_delta > 0 THEN
                avg_cost := ROUND((avg_cost * qty + mv.cost_delta) / (qty + mv.quantity_delta), 6);
                qty := qty + mv.quantity_delta;
            ELSE
                qty := GREATEST(0, qty + mv.quantity_delta);
                IF qty = 0 THEN
                    avg_cost := 0;
                END IF;
            END IF;
            last_at := mv.created_at;
        END LOOP;

        INSERT INTO trading.positions (account_id, instrument_id, quantity, avg_cost, updated_at)
        VALUES (pos.account_id, pos.instrument_id, qty, avg_cost, last_at)
        ON CONFLICT (account_id, instrument_id) DO UPDATE SET
            quantity = EXCLUDED.quantity,
            avg_cost = EXCLUDED.avg_cost,
            updated_at = EXCLUDED.updated_at;
    END LOOP;
END $$;
