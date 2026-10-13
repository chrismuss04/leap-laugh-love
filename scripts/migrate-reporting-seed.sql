-- Reporting seed: apply the new seed data to an existing database. Safe to rerun.
--   1. the 40 mock clients and their order history (as in seed_iam.sql and seed_trading.sql);
--   2. the 10 named clients' registration dates, as seed_iam.sql now seeds them;
--   3. seed_reporting.sql.
-- Restart order-app afterwards so SeededFillService books the new fills.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-reporting-seed.sql

BEGIN;

-- ---- 1. Analytics mock clients (seed_iam.sql) -------------------------------------------------
CREATE TEMP TABLE mock_client AS
SELECT n,
       (ARRAY['Liam', 'Olivia', 'Noah', 'Ava', 'Ethan', 'Sophia', 'Mason', 'Mia', 'Lucas', 'Amelia',
              'Logan', 'Harper', 'Elijah', 'Evelyn', 'James', 'Abigail', 'Benjamin', 'Ella', 'Alexander', 'Scarlett',
              'Daniel', 'Chloe', 'Matthew', 'Lily', 'Samuel', 'Zoe', 'Owen', 'Nora', 'Caleb', 'Hazel',
              'Isaac', 'Aria', 'Nathan', 'Layla', 'Ryan', 'Riley', 'Dylan', 'Stella', 'Leo', 'Maya'])[n] AS first_name,
       (ARRAY['Garcia', 'Martinez', 'Robinson', 'Clark', 'Rodriguez', 'Lewis', 'Lee', 'Walker', 'Hall', 'Allen',
              'Young', 'Hernandez', 'King', 'Wright', 'Lopez', 'Hill', 'Scott', 'Green', 'Adams', 'Baker',
              'Gonzalez', 'Nelson', 'Carter', 'Mitchell', 'Perez', 'Roberts', 'Turner', 'Phillips', 'Campbell', 'Parker',
              'Evans', 'Edwards', 'Collins', 'Stewart', 'Sanchez', 'Morris', 'Rogers', 'Reed', 'Cook', 'Morgan'])[n] AS last_name,
       (ARRAY['New York', 'Boston', 'Chicago', 'Seattle', 'Austin', 'Denver', 'Atlanta', 'Miami', 'Portland', 'Minneapolis'])[1 + n % 10] AS city,
       (ARRAY['NY', 'MA', 'IL', 'WA', 'TX', 'CO', 'GA', 'FL', 'OR', 'MN'])[1 + n % 10] AS state_region,
       (ARRAY['10001', '02108', '60601', '98101', '78701', '80202', '30303', '33131', '97201', '55401'])[1 + n % 10] AS postal_code,
       (ARRAY['NOVICE', 'INTERMEDIATE', 'ADVANCED'])[1 + n % 3] AS experience_level,
       100000.00 + (n % 4) * 50000.00 AS initial_deposit_amount,
       date_trunc('day', NOW()) - make_interval(days => 352 - (n - 1) * 8)
           + make_interval(hours => 9 + n % 8, mins => (n * 13) % 60) AS registered_at
FROM generate_series(1, 40) AS n;

INSERT INTO iam.clients (email, phone, status, created_at)
SELECT lower(first_name || '.' || last_name) || '@leap.com', format('(646) 555-%s', lpad((200 + n)::text, 4, '0')), 'ACTIVE', registered_at
FROM mock_client
ON CONFLICT (email) DO NOTHING;

INSERT INTO iam.client_profile (client_id, full_name, date_of_birth, ssn, address_line1, city, state_region, postal_code, country_code, experience_level, initial_deposit_amount)
SELECT c.client_id, m.first_name || ' ' || m.last_name, DATE '1970-01-01' + (n * 263) % 10000, format('900-20-%s', lpad(n::text, 4, '0')),
       format('%s Market Street', 100 + n), m.city, m.state_region, m.postal_code, 'US', m.experience_level, m.initial_deposit_amount
FROM mock_client m
JOIN iam.clients c ON c.email = lower(m.first_name || '.' || m.last_name) || '@leap.com'
ON CONFLICT (client_id) DO NOTHING;

INSERT INTO iam.client_credentials (client_id, password_hash, failed_attempts)
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0
FROM mock_client m
JOIN iam.clients c ON c.email = lower(m.first_name || '.' || m.last_name) || '@leap.com'
ON CONFLICT (client_id) DO NOTHING;

DROP TABLE mock_client;

-- ---- 1. Their accounts, deposits and orders (seed_trading.sql) --------------------------------
INSERT INTO trading.accounts (client_id, account_number, status, base_currency, trading_enabled, created_at)
SELECT c.client_id, format('ACC-%s-01', 100 + (right(c.phone, 4)::int - 200)), 'ACTIVE', 'USD', TRUE,
       c.created_at + INTERVAL '1 day'
FROM iam.clients c
WHERE c.phone LIKE '(646) 555-02__'
ON CONFLICT (account_number) DO NOTHING;

INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, entry_type, amount, currency, created_at, description)
SELECT format('dddddddd-dddd-dddd-dddd-ffffffff%s', lpad(substr(a.account_number, 6, 2), 4, '0'))::UUID,
       a.account_id, 'DEPOSIT', cp.initial_deposit_amount, 'USD', a.created_at, 'Initial account funding'
FROM trading.accounts a
JOIN iam.client_profile cp ON cp.client_id = a.client_id
WHERE a.account_number ~ '^ACC-1[0-4][0-9]-01$'
ON CONFLICT (cash_ledger_id) DO NOTHING;

-- Orders per client: BUY A, SELL half of A, BUY B, SELL half of B, BUY C, a rejection, SELL half
-- of C, BUY D. AAPL trades most.
WITH mock AS (
    SELECT a.account_id, substr(a.account_number, 6, 2)::int AS n, current_date - a.created_at::date AS days_open
    FROM trading.accounts a
    WHERE a.account_number ~ '^ACC-1[0-4][0-9]-01$'
),
plan AS (
    SELECT m.account_id, m.n, k,
           -- Instrument slot: buy/sell pairs share one; orders 6 and 8 get their own.
           CASE k WHEN 1 THEN 1 WHEN 2 THEN 1 WHEN 3 THEN 2 WHEN 4 THEN 2 WHEN 8 THEN 4 ELSE 3 END AS p,
           -- Spread from the day after opening (k = 1) to yesterday (k = 8).
           m.days_open - 1 - ((m.days_open - 2) * k) / 9 AS days_ago
    FROM mock m
    CROSS JOIN generate_series(1, 8) AS k
    WHERE m.n % 5 <> 0
),
orders AS (
    SELECT format('cccccccc-cccc-cccc-%s-%s', lpad(n::text, 4, '0'), lpad(k::text, 12, '0'))::UUID AS order_id,
           account_id, k,
           (ARRAY['AAPL', 'AAPL', 'AAPL', 'MSFT', 'MSFT', 'TSLA', 'GOOGL'])[1 + (n * 3 + p) % 7] AS symbol,
           CASE WHEN k IN (2, 4, 7) OR (k = 6 AND n % 4 = 1) THEN 'SELL' ELSE 'BUY' END AS side,
           CASE
               WHEN k = 6 AND n % 4 = 0 THEN 5000
               WHEN k = 6 AND n % 4 = 1 THEN 1000
               WHEN k IN (2, 4, 7) THEN (6 + (n * 5 + p * 7) % 15) / 2
               ELSE 6 + (n * 5 + p * 7) % 15
           END AS quantity,
           CASE WHEN k = 6 THEN 'REJECTED' ELSE 'FILLED' END AS status,
           CASE WHEN k = 6 THEN (ARRAY['Insufficient funds', 'Insufficient position quantity',
                                       'Price moved beyond your price tolerance', 'Market quote unavailable'])[1 + n % 4]
           END AS rejection_reason,
           date_trunc('day', NOW()) - make_interval(days => days_ago)
               + make_interval(hours => 9 + (n + k) % 7, mins => (n * 7 + k * 11) % 50) AS submitted_at
    FROM plan
)
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, accepted_at,
                            rejected_at, filled_at, rejection_reason)
SELECT o.order_id, o.account_id, i.instrument_id, o.side, o.quantity, o.status, o.submitted_at,
       CASE WHEN o.status = 'REJECTED' THEN NULL ELSE o.submitted_at + INTERVAL '1 minute' END,
       CASE WHEN o.status = 'REJECTED' THEN o.submitted_at + INTERVAL '1 minute' END,
       CASE WHEN o.status = 'REJECTED' THEN NULL ELSE o.submitted_at + INTERVAL '2 minutes' END,
       o.rejection_reason
FROM orders o
JOIN trading.instruments i ON i.symbol = o.symbol
ON CONFLICT (order_id) DO NOTHING;

-- Executions for the mock clients' rejected orders.
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
SELECT ('eeeeeeee' || substr(o.order_id::text, 9))::UUID, o.order_id, NULL, NULL, o.status, o.rejected_at,
       o.rejection_reason || ' - order rejected'
FROM trading.orders o
WHERE o.order_id::text LIKE 'cccccccc-cccc-cccc-%' AND o.rejected_at IS NOT NULL
ON CONFLICT (execution_id) DO NOTHING;

-- ---- 2. Original seed clients' registration dates ----------------------------------------------
-- Alice's account is dated relative to database creation, so hers stays 5 days before it.
UPDATE iam.clients c
SET created_at = v.registered_at
FROM (VALUES
    ('alice.johnson@leap.com', (SELECT date_trunc('day', created_at) - INTERVAL '5 days' + TIME '10:00'
                                FROM trading.accounts WHERE account_number = 'ACC-001-01')),
    ('bob.smith@leap.com', TIMESTAMPTZ '2025-04-04 10:00:00+00'),
    ('carol.williams@leap.com', TIMESTAMPTZ '2025-04-11 10:00:00+00'),
    ('david.brown@leap.com', TIMESTAMPTZ '2025-05-02 10:00:00+00'),
    ('emma.davis@leap.com', TIMESTAMPTZ '2025-05-09 10:00:00+00'),
    ('frank.miller@leap.com', TIMESTAMPTZ '2025-05-16 10:00:00+00'),
    ('grace.wilson@leap.com', TIMESTAMPTZ '2025-05-30 10:00:00+00'),
    ('henry.taylor@leap.com', TIMESTAMPTZ '2025-06-06 10:00:00+00'),
    ('iris.anderson@leap.com', TIMESTAMPTZ '2025-06-13 10:00:00+00'),
    ('jack.thomas@leap.com', TIMESTAMPTZ '2025-07-04 10:00:00+00')
) AS v (email, registered_at)
WHERE c.email = v.email
  AND c.created_at > v.registered_at;

-- ---- 3. Reporting schema (seed_reporting.sql) --------------------------------------------------
-- One registration per client, dated by iam.clients.created_at.
INSERT INTO reporting.clients (client_id, registered_at)
SELECT c.client_id, c.created_at
FROM iam.clients c
ON CONFLICT (client_id) DO NOTHING;

-- Rejected orders, as reporting-etl writes them.
INSERT INTO reporting.orders (order_id, account_id, client_id, instrument_id, symbol, side, quantity, status,
                              fill_price, rejection_reason, submitted_at, completed_at)
SELECT o.order_id, o.account_id, a.client_id, o.instrument_id, i.symbol, o.side, o.quantity, o.status,
       NULL, o.rejection_reason, o.submitted_at, o.rejected_at
FROM trading.orders o
JOIN trading.accounts a ON a.account_id = o.account_id
JOIN trading.instruments i ON i.instrument_id = o.instrument_id
WHERE o.status = 'REJECTED' AND o.rejected_at IS NOT NULL
ON CONFLICT (order_id) DO NOTHING;

COMMIT;
