-- Session Timeout & Revocation: add session storage to an existing database.
-- Prerequisite: iam.clients already exists. Safe to rerun after successful application.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-client-sessions.sql
-- last_activity_at will be maintained by the backend activity endpoint.
-- expires_at is the absolute session expiry; the 10-minute idle limit is enforced in application code.
-- A non-null revoked_at invalidates the session without deleting its record.

BEGIN;

-- Session Timeout & Revocation: one row per login; never store the raw JWT.
CREATE TABLE IF NOT EXISTS iam.client_sessions (
    session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id UUID NOT NULL
        REFERENCES iam.clients (client_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT chk_client_sessions_activity CHECK (last_activity_at >= created_at),
    CONSTRAINT chk_client_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_client_sessions_revocation CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

-- Session Timeout & Revocation: support session lookup by client and expired-session cleanup.
CREATE INDEX IF NOT EXISTS idx_client_sessions_client_id
    ON iam.client_sessions (client_id);
CREATE INDEX IF NOT EXISTS idx_client_sessions_expires_at
    ON iam.client_sessions (expires_at);

COMMIT;
