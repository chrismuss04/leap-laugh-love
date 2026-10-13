-- Order History Sort Index: order history is listed and filtered by when each order completed
-- (filled, rejected, cancelled or expired - see Order#getCompletedAt), which the existing
-- submitted_at index does not cover. Safe to rerun. Fine to skip while order counts are small.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-order-history-index.sql

CREATE INDEX IF NOT EXISTS idx_orders_account_completed_at_order_id
    ON trading.orders (account_id, (COALESCE(filled_at, rejected_at, cancelled_at, expired_at, submitted_at)) DESC, order_id DESC);
