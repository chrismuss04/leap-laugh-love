-- Account Settings: add notification preferences to an existing database.
-- Prerequisite: iam.client_profile already exists. Safe to rerun.
-- Fresh databases get the same columns from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker-compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-notification-preferences.sql

ALTER TABLE iam.client_profile
    ADD COLUMN IF NOT EXISTS notify_order_fills BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS notify_price_alerts BOOLEAN NOT NULL DEFAULT TRUE;
