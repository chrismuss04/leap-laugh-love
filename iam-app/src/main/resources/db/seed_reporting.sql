-- Reporting Schema Seed Data
--
-- The reporting schema is normally loaded by reporting-etl from Kafka events, which seed data
-- never produces: seeded clients didn't register through iam-app, so no client-register event
-- exists for them, and seeded rejected orders were never submitted. This copies both into the
-- reporting schema so the commercial analyst's reports show the seeded history.
--
-- Seeded FILLED orders are not copied here: SeededFillService publishes each one's order event
-- once it books the fill at startup (see seed_trading.sql), and reporting-etl loads it from there.
--
-- Runs after every other seed (as 08-seed-reporting.sql, after the E2E seed's 07 where that is
-- mounted), so it covers all of their clients and orders. Idempotent: ON CONFLICT DO NOTHING keeps
-- re-runs, and rows reporting-etl has already loaded, as they are.

-- Registrations: one row per seeded client, registered when its iam.clients row was created.
INSERT INTO reporting.clients (client_id, registered_at)
SELECT c.client_id, c.created_at
FROM iam.clients c
ON CONFLICT (client_id) DO NOTHING;

-- Rejected orders, shaped as reporting-etl writes them: no fill price, completed when rejected.
INSERT INTO reporting.orders (order_id, account_id, client_id, instrument_id, symbol, side, quantity, status,
                              fill_price, rejection_reason, submitted_at, completed_at)
SELECT o.order_id, o.account_id, a.client_id, o.instrument_id, i.symbol, o.side, o.quantity, o.status,
       NULL, o.rejection_reason, o.submitted_at, o.rejected_at
FROM trading.orders o
JOIN trading.accounts a ON a.account_id = o.account_id
JOIN trading.instruments i ON i.instrument_id = o.instrument_id
WHERE o.status = 'REJECTED' AND o.rejected_at IS NOT NULL
ON CONFLICT (order_id) DO NOTHING;
