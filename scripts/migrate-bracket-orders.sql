-- Bracket Orders: a buy can carry a take-profit and a stop-loss; when it fills, two linked sell
-- orders (one cancels the other) protect the position. Safe to rerun after successful
-- application. Run scripts/migrate-order-types.sql first.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-bracket-orders.sql

BEGIN;

ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS bracket_take_profit_price NUMERIC(18,6)
    CHECK (bracket_take_profit_price IS NULL OR bracket_take_profit_price > 0);
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS bracket_stop_loss_price NUMERIC(18,6)
    CHECK (bracket_stop_loss_price IS NULL OR bracket_stop_loss_price > 0);
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS parent_order_id UUID;
ALTER TABLE trading.orders ADD COLUMN IF NOT EXISTS oco_group_id UUID;

CREATE INDEX IF NOT EXISTS idx_orders_oco_group
    ON trading.orders (oco_group_id) WHERE oco_group_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_orders_parent
    ON trading.orders (parent_order_id) WHERE parent_order_id IS NOT NULL;

COMMIT;
