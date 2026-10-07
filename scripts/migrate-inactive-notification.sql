-- Inactive Account Email: record when a client was emailed about an inactive account. Safe to rerun.
-- docker-compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-inactive-notification.sql

ALTER TABLE trading.accounts ADD COLUMN IF NOT EXISTS inactive_notified_at TIMESTAMPTZ;
