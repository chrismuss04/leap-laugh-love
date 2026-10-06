-- Password Recovery: add reset-link storage to an existing database.
-- Prerequisite: iam.clients already exists. Safe to rerun after successful application.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-password-reset-tokens.sql
-- token_hash is the SHA-256 of the token in the emailed link; the token itself is never stored.
-- A non-null used_at means the link can no longer reset the password.

BEGIN;

-- Password Recovery: one row per emailed reset link. Only a SHA-256 hash of the token is stored,
-- so reading this table does not reveal a usable link.
CREATE TABLE IF NOT EXISTS iam.password_reset_tokens (
    token_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id UUID NOT NULL
        REFERENCES iam.clients (client_id) ON DELETE RESTRICT,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    -- Set when the link resets the password, or when a newer link replaces it.
    used_at TIMESTAMPTZ,
    CONSTRAINT chk_password_reset_tokens_expiry CHECK (expires_at > created_at)
);

-- Password Recovery: find a client's outstanding links when a new one replaces them.
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_client_id
    ON iam.password_reset_tokens (client_id);

COMMIT;
