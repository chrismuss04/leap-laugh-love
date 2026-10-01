-- Inactive Accounts: add the inactivity flag to an existing database.
-- Prerequisite: trading.accounts already exists. Safe to rerun after successful application.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-inactive-accounts.sql
-- inactive_since starts NULL on every account; account-app's nightly job fills it in.

BEGIN;

-- Inactive Accounts: when the balance went to 0, once it has stayed there past the threshold.
ALTER TABLE trading.accounts
    ADD COLUMN IF NOT EXISTS inactive_since TIMESTAMPTZ;

COMMIT;
