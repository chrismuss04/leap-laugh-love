-- Registration: add email-confirmed registration and unique phone numbers to an existing database.
-- Prerequisite: iam.clients already exists. Safe to rerun after successful application.
-- Fresh databases get the same definitions from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-registration-verification.sql
--
-- The index fails if two clients already share a phone number; nothing is changed in that case.
-- Find them with:
--   SELECT phone, COUNT(*) FROM iam.clients WHERE phone IS NOT NULL GROUP BY phone HAVING COUNT(*) > 1;
-- and clear or correct all but one before running this again.

BEGIN;

-- Registration: a phone number belongs to one client, like an email or an SSN. Stored in the
-- format iam-app normalizes to, so the same number can't be registered twice in two spellings.
CREATE UNIQUE INDEX IF NOT EXISTS uq_clients_phone
    ON iam.clients (phone);

-- Registration: an application waiting for the applicant to open the link they were emailed.
-- It becomes a client only then, so nothing here reserves an email, phone number or SSN: those
-- are unique across iam.clients / iam.client_profile, not across applications. Only a SHA-256
-- hash of the link's token is stored. Rows are deleted when confirmed or once they expire.
CREATE TABLE IF NOT EXISTS iam.pending_registrations (
    registration_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash TEXT NOT NULL UNIQUE,
    -- One outstanding application per email: a newer one replaces it, so only the newest link works.
    email TEXT NOT NULL UNIQUE,
    phone TEXT,
    full_name TEXT NOT NULL,
    date_of_birth DATE NOT NULL,
    ssn CHAR(11) NOT NULL,
    address_line1 TEXT NOT NULL,
    address_line2 TEXT,
    city TEXT NOT NULL,
    state_region TEXT,
    postal_code TEXT NOT NULL,
    country_code CHAR(2) NOT NULL,
    experience_level TEXT NOT NULL
        CHECK (experience_level IN ('NOVICE', 'INTERMEDIATE', 'ADVANCED')),
    initial_deposit_amount NUMERIC(18,2) NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_pending_registrations_expiry CHECK (expires_at > created_at)
);

COMMIT;
