-- Staff roles: add staff session storage to an existing database, so analysts and trading
-- operations staff can sign in. Prerequisite: iam.reporting_service_credentials already exists.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Safe to rerun after successful application. Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-staff-sessions.sql
-- Local staff accounts come from IAM's db/seed_iam.sql; this adds no accounts of its own.

BEGIN;

-- Staff roles: one row per staff login, kept apart from client_sessions so a staff token can
-- only ever match a staff session. Same lifetime rules as client_sessions; never store the raw JWT.
CREATE TABLE IF NOT EXISTS iam.staff_sessions (
    session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id UUID NOT NULL
        REFERENCES iam.reporting_service_credentials (service_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT chk_staff_sessions_activity CHECK (last_activity_at >= created_at),
    CONSTRAINT chk_staff_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_staff_sessions_revocation CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX IF NOT EXISTS idx_staff_sessions_staff_id
    ON iam.staff_sessions (staff_id);
CREATE INDEX IF NOT EXISTS idx_staff_sessions_expires_at
    ON iam.staff_sessions (expires_at);

COMMIT;
