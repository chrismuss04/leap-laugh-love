-- Account Settings: add notification preferences to an existing database. Safe to rerun.
-- docker-compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-notification-preferences.sql

ALTER TABLE iam.client_profile
    ADD COLUMN IF NOT EXISTS notify_order_fills BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS notify_price_alerts BOOLEAN NOT NULL DEFAULT TRUE;
