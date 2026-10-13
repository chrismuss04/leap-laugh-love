-- DEV ONLY: fills the reporting schema with 24 months of synthetic clients and orders, so the
-- analyst reports in reporting-app have history to show on a fresh local stack. Never loaded by
-- the database's init scripts, and never to be run against a database with real data.
-- Run from the repository root:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/seed-reporting-history.sql
--
-- The rows are written straight into reporting.clients and reporting.orders, bypassing Kafka and
-- the ETL, so the script rebuilds reporting.client_months from reporting.orders at the end, the
-- same way scripts/migrate-reporting-client-months.sql does. None of these clients or orders
-- exist in iam or trading.
--
-- Months are counted back from the current UTC month, so the history always ends last month.
-- Shape, chosen so every report has something to show:
--   * sign-ups grow from 12 a month two years ago to 58 last month;
--   * about 70% of clients order in their sign-up month, then fewer each month after;
--   * about 5% are "whales": they keep trading and place orders around 25 times larger;
--   * about 5% of orders are rejected at settlement.
-- IDs are derived from fixed names and the random sequence is seeded, so a rerun produces the
-- same rows and inserts nothing new.

BEGIN;

SELECT setseed(0.42);

CREATE TEMP TABLE seed_clients ON COMMIT DROP AS
SELECT md5('reporting-seed-client-' || months_ago || '-' || n)::uuid AS client_id,
       -- A random instant in the sign-up month, in UTC.
       (month_start + random() * (month_start + interval '1 month' - month_start)) AT TIME ZONE 'UTC' AS registered_at,
       months_ago,
       random() < 0.05 AS whale
FROM generate_series(1, 24) AS months_ago
CROSS JOIN LATERAL (
    SELECT date_trunc('month', now() AT TIME ZONE 'UTC') - make_interval(months => months_ago) AS month_start
) AS sign_up
CROSS JOIN LATERAL generate_series(1, 60 - 2 * months_ago) AS n;

INSERT INTO reporting.clients (client_id, registered_at)
SELECT client_id, registered_at FROM seed_clients
ON CONFLICT (client_id) DO NOTHING;

WITH client_months AS (
    -- Every month from the client's sign-up month to last month: k months after signing up.
    SELECT client_id, registered_at, whale, k,
           date_trunc('month', registered_at AT TIME ZONE 'UTC') + make_interval(months => k) AS month_start
    FROM seed_clients
    CROSS JOIN LATERAL generate_series(0, months_ago - 1) AS k
),
active_months AS (
    SELECT * FROM client_months
    WHERE random() < CASE WHEN whale THEN 0.9 WHEN k = 0 THEN 0.7 ELSE 0.5 * power(0.92, k) END
),
seed_orders AS (
    SELECT active_months.*, i,
           -- In the sign-up month an order can only come after signing up.
           CASE WHEN k = 0 THEN registered_at AT TIME ZONE 'UTC' ELSE month_start END AS earliest,
           month_start + interval '1 month' AS latest
    FROM active_months
    CROSS JOIN LATERAL generate_series(1, 1 + floor(random() * CASE WHEN whale THEN 8 ELSE 4 END)::int) AS i
),
priced AS (
    SELECT client_id, k, i, latest,
           earliest + random() * (latest - earliest) AS placed,
           (ARRAY['AAPL', 'MSFT', 'NVDA', 'AMZN', 'GOOGL', 'META', 'TSLA', 'JPM', 'V', 'XOM'])[1 + floor(random() * 10)::int] AS symbol,
           CASE WHEN random() < 0.5 THEN 'BUY' ELSE 'SELL' END AS side,
           (1 + floor(random() * 40)) * CASE WHEN whale THEN 25 ELSE 1 END AS quantity,
           round((20 + random() * 480)::numeric, 2) AS price,
           random() < 0.05 AS rejected
    FROM seed_orders
)
INSERT INTO reporting.orders (order_id, account_id, client_id, instrument_id, symbol, side, quantity, status,
                              fill_price, rejection_reason, submitted_at, completed_at)
SELECT md5('reporting-seed-order-' || client_id || '-' || k || '-' || i)::uuid,
       md5('reporting-seed-account-' || client_id)::uuid,
       client_id,
       md5('reporting-seed-instrument-' || symbol)::uuid,
       symbol,
       side,
       quantity,
       CASE WHEN rejected THEN 'REJECTED' ELSE 'FILLED' END,
       CASE WHEN rejected THEN NULL ELSE price END,
       CASE WHEN rejected THEN 'Settlement failed: insufficient funds' END,
       placed AT TIME ZONE 'UTC',
       -- Settled a moment after it was placed; still within the month.
       least(placed + interval '400 milliseconds', latest - interval '1 microsecond') AT TIME ZONE 'UTC'
FROM priced
ON CONFLICT (order_id) DO NOTHING;

-- Same rebuild as scripts/migrate-reporting-client-months.sql, holding off the ETL while it runs.
LOCK TABLE reporting.orders IN SHARE MODE;

DELETE FROM reporting.client_months;

INSERT INTO reporting.client_months (client_id, month, order_count, filled_count, filled_value)
SELECT client_id,
       date_trunc('month', completed_at AT TIME ZONE 'UTC')::date,
       count(*),
       count(*) FILTER (WHERE status = 'FILLED'),
       coalesce(sum(quantity * fill_price) FILTER (WHERE status = 'FILLED'), 0)
FROM reporting.orders
-- Executed orders only, as reporting-etl keeps it: CANCELLED and EXPIRED never executed.
WHERE status IN ('FILLED', 'REJECTED')
GROUP BY 1, 2;

COMMIT;
