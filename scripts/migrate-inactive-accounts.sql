-- Inactive Accounts: add the inactivity flag to an existing database. Safe to rerun.
-- docker-compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-inactive-accounts.sql

ALTER TABLE trading.accounts ADD COLUMN IF NOT EXISTS inactive_since TIMESTAMPTZ;
