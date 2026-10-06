-- User Reporting: add registration storage without removing existing application data.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Safe to rerun after successful application. Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-reporting-clients.sql

BEGIN;

CREATE SCHEMA IF NOT EXISTS reporting;

-- User Reporting: one row per registered client, loaded from client-register events.
-- No IAM foreign key: reporting replay must not depend on operational client rows.
CREATE TABLE IF NOT EXISTS reporting.clients (
    client_id UUID PRIMARY KEY,
    registered_at TIMESTAMPTZ NOT NULL,
    -- ETL arrival time is separate from the original registration time.
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Supports registration counts over a reporting period.
CREATE INDEX IF NOT EXISTS idx_reporting_clients_registered_at
    ON reporting.clients (registered_at);

COMMIT;
