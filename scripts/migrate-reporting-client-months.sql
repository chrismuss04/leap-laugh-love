-- Analyst Reporting: add the monthly per-client rollup and rebuild it from reporting.orders.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql, and fill it
-- as orders load. Safe to rerun at any time: each run rebuilds the rollup from scratch.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-reporting-client-months.sql
--
-- Run it once the reporting-etl that writes this table is deployed. That ETL retries (without
-- committing its Kafka offset) until the table exists, so it loses nothing while waiting. An
-- older ETL still running afterwards would load orders without adding them to the rollup.

BEGIN;

-- Same definition as the schema file; see the comments there.
CREATE TABLE IF NOT EXISTS reporting.client_months (
    client_id UUID NOT NULL,
    month DATE NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    order_count INTEGER NOT NULL CHECK (order_count > 0),
    filled_count INTEGER NOT NULL CHECK (filled_count >= 0 AND filled_count <= order_count),
    filled_value NUMERIC NOT NULL CHECK (filled_value >= 0),
    PRIMARY KEY (client_id, month)
);

CREATE INDEX IF NOT EXISTS idx_reporting_client_months_month
    ON reporting.client_months (month);

-- Holds off the ETL's inserts until COMMIT, so no order can land between the read below and the
-- rebuilt rollup becoming visible. Reads carry on; the ETL just waits, then adds to the rebuild.
LOCK TABLE reporting.orders IN SHARE MODE;

DELETE FROM reporting.client_months;

INSERT INTO reporting.client_months (client_id, month, order_count, filled_count, filled_value)
SELECT client_id,
       date_trunc('month', completed_at AT TIME ZONE 'UTC')::date,
       count(*),
       count(*) FILTER (WHERE status = 'FILLED'),
       coalesce(sum(quantity * fill_price) FILTER (WHERE status = 'FILLED'), 0)
FROM reporting.orders
GROUP BY 1, 2;

COMMIT;
