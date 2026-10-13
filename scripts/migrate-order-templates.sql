-- Order Templates: saved ticket setups, stored as percent offsets so they follow the client across
-- devices. Safe to rerun after successful application. Run scripts/migrate-order-types.sql first.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-order-templates.sql

BEGIN;

CREATE TABLE IF NOT EXISTS trading.order_templates (
    template_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id UUID NOT NULL
        REFERENCES iam.clients (client_id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 40),
    side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
    order_type TEXT NOT NULL
        CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT', 'TRAILING_STOP')),
    time_in_force TEXT NOT NULL DEFAULT 'DAY' CHECK (time_in_force IN ('DAY', 'GTC')),
    limit_offset_pct NUMERIC(7,2),
    stop_offset_pct NUMERIC(7,2),
    trail_percent NUMERIC(5,2) CHECK (trail_percent IS NULL OR (trail_percent > 0 AND trail_percent <= 50)),
    take_profit_pct NUMERIC(7,2) CHECK (take_profit_pct IS NULL OR take_profit_pct > 0),
    stop_loss_pct NUMERIC(5,2) CHECK (stop_loss_pct IS NULL OR (stop_loss_pct > 0 AND stop_loss_pct < 100)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT order_templates_bracket_pair CHECK ((take_profit_pct IS NULL) = (stop_loss_pct IS NULL)),
    CONSTRAINT order_templates_name_unique UNIQUE (client_id, name)
);

COMMIT;
