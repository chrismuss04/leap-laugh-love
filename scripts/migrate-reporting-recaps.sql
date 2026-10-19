-- Monthly Client Recaps: add the sector reference table and the stored recaps table.
-- Fresh databases get the same definitions from IAM's db/leap_laugh_love_schema.sql. Safe to rerun:
-- both tables are created only if missing.
-- Run from the repository root in Linux, then load the sectors:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-reporting-recaps.sql
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < iam-app/src/main/resources/db/seed_reporting_sectors.sql
--
-- Run it before deploying the reporting-app that builds recaps. Recaps for months before this
-- runs are built on demand, or by the next monthly job.

BEGIN;

-- Same definitions as the schema file; see the comments there.
CREATE TABLE IF NOT EXISTS reporting.instrument_sectors (
    symbol TEXT PRIMARY KEY,
    sector TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS reporting.client_recaps (
    client_id UUID NOT NULL,
    month DATE NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    schema_version SMALLINT NOT NULL CHECK (schema_version > 0),
    payload JSONB NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (client_id, month)
);

COMMIT;
