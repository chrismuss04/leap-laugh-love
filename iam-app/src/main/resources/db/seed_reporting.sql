-- Reporting Schema Seed Data
--
-- reporting-etl loads this schema from Kafka events, which seeded clients and rejected orders never
-- produce, so this copies them in. Filled orders arrive via SeededFillService's events instead.
-- Mounted as 08 so it runs after every other seed, E2E's 07 included. Idempotent.

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
