CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS trading;
CREATE SCHEMA IF NOT EXISTS marketdata;

CREATE TABLE IF NOT EXISTS iam.clients (
    client_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT NOT NULL UNIQUE,
    phone TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('PENDING', 'ACTIVE', 'LOCKED', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS iam.client_profile (
    client_id UUID PRIMARY KEY
        REFERENCES iam.clients (client_id) ON DELETE CASCADE,
    full_name TEXT NOT NULL,
    date_of_birth DATE NOT NULL,
    ssn CHAR(11) NOT NULL UNIQUE,
    -- LLL-117: renamed from address_line_1/address_line_2 to match the column names
    -- Client.java's @Column mappings actually use (address_line1/address_line2) - the
    -- underscored names here didn't match the entity, so registration failed with a
    -- "column not found" error against a real database.
    address_line1 TEXT NOT NULL,
    address_line2 TEXT,
    city TEXT NOT NULL,
    state_region TEXT,
    postal_code TEXT NOT NULL,
    country_code CHAR(2) NOT NULL,
    experience_level TEXT NOT NULL
        CHECK (experience_level IN ('NOVICE', 'INTERMEDIATE', 'ADVANCED')),
    initial_deposit_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS iam.client_credentials (
    client_id UUID PRIMARY KEY
        REFERENCES iam.clients (client_id) ON DELETE CASCADE,
    password_hash TEXT NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS trading.accounts (
    account_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id UUID NOT NULL
        REFERENCES iam.clients (client_id) ON DELETE RESTRICT,
    account_number TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('PENDING', 'ACTIVE', 'BLOCKED', 'CLOSED')),
    base_currency CHAR(3) NOT NULL DEFAULT 'USD',
    trading_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    -- Saved price tolerance: an order is rejected if the price moves more than this percent
    -- (either way) between the client's quote and execution. NULL means no saved tolerance.
    max_slippage_pct NUMERIC(5,2)
        CHECK (max_slippage_pct IS NULL OR (max_slippage_pct >= 0 AND max_slippage_pct <= 100)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Backs the account lookup by client_id used by the order history query.
CREATE INDEX IF NOT EXISTS idx_accounts_client_id
    ON trading.accounts (client_id);

CREATE TABLE IF NOT EXISTS trading.instruments (
    instrument_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol TEXT NOT NULL,
    instrument_name TEXT NOT NULL,
    asset_class TEXT NOT NULL
        CHECK (asset_class IN ('EQUITY', 'FX', 'CRYPTO')),
    market TEXT NOT NULL,
    currency CHAR(3) NOT NULL,
    is_tradable BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (symbol, market)
);

CREATE TABLE IF NOT EXISTS trading.orders (
    order_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL
        REFERENCES trading.accounts (account_id) ON DELETE RESTRICT,
    instrument_id UUID NOT NULL
        REFERENCES trading.instruments (instrument_id) ON DELETE RESTRICT,
    side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    status TEXT NOT NULL
        CHECK (status IN ('SUBMITTED', 'OPEN', 'ACCEPTED', 'REJECTED', 'FILLED', 'CANCELLED', 'EXPIRED')),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    accepted_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    filled_at TIMESTAMPTZ,
    rejection_reason TEXT,
    -- The price the client was quoted and the tolerance applied to it, kept so a price-move
    -- rejection can be explained later. NULL when the order carried no quote.
    quoted_price NUMERIC(18,6) CHECK (quoted_price IS NULL OR quoted_price > 0),
    max_slippage_pct NUMERIC(5,2)
        CHECK (max_slippage_pct IS NULL OR (max_slippage_pct >= 0 AND max_slippage_pct <= 100)),
    -- Order types: MARKET fills at once; the others wait OPEN until the market reaches their
    -- price (see OrderTriggerEngine). A limit caps the fill price; a stop sets when the order
    -- starts working.
    order_type TEXT NOT NULL DEFAULT 'MARKET'
        CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT', 'TRAILING_STOP')),
    limit_price NUMERIC(18,6) CHECK (limit_price IS NULL OR limit_price > 0),
    stop_price NUMERIC(18,6) CHECK (stop_price IS NULL OR stop_price > 0),
    -- NULL for a market order, which never waits.
    time_in_force TEXT CHECK (time_in_force IS NULL OR time_in_force IN ('DAY', 'GTC')),
    expires_at TIMESTAMPTZ,
    -- When a stop order's stop price was reached.
    triggered_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    expired_at TIMESTAMPTZ,
    -- Cash an OPEN buy holds back from buying power, so open orders can't spend it twice.
    reserved_cash NUMERIC(18,2) CHECK (reserved_cash IS NULL OR reserved_cash >= 0),
    -- Trailing stops: the stop follows the best price seen at a fixed amount or percent away.
    -- trail_peak is that best price (the high for a sell, the low for a buy) and stop_price is the
    -- stop it sets, which only ever moves in the order's favour (see OrderTriggerEngine).
    trail_amount NUMERIC(18,6) CHECK (trail_amount IS NULL OR trail_amount > 0),
    trail_percent NUMERIC(5,2) CHECK (trail_percent IS NULL OR (trail_percent > 0 AND trail_percent <= 50)),
    trail_peak NUMERIC(18,6) CHECK (trail_peak IS NULL OR trail_peak > 0),
    -- Fill alerts: set when an order goes OPEN, cleared once its outcome has been emailed (or the
    -- client opted out). Orders that execute the moment they are placed never get it. See
    -- OrderFillMailer.
    notice_pending BOOLEAN NOT NULL DEFAULT FALSE,
    notified_at TIMESTAMPTZ,
    -- Bracket orders: a buy can carry a take-profit and a stop-loss price. When it fills, order-app
    -- opens two sell orders (a LIMIT and a STOP) sharing one oco_group_id and pointing back at it
    -- through parent_order_id; when one fills the other is cancelled (see BracketService).
    bracket_take_profit_price NUMERIC(18,6) CHECK (bracket_take_profit_price IS NULL OR bracket_take_profit_price > 0),
    bracket_stop_loss_price NUMERIC(18,6) CHECK (bracket_stop_loss_price IS NULL OR bracket_stop_loss_price > 0),
    parent_order_id UUID,
    oco_group_id UUID,
    -- Market hours: an extended-hours LIMIT order may also fill in pre-market (4:00-9:30 ET) and
    -- after-hours (16:00-20:00 ET); every other order only executes in regular hours.
    extended_hours BOOLEAN NOT NULL DEFAULT FALSE,
    -- A market order queued while the market is closed fills at the open only within this price:
    -- the most a buy pays, the least a sell takes.
    collar_price NUMERIC(18,6) CHECK (collar_price IS NULL OR collar_price > 0),
    CONSTRAINT orders_limit_price_by_type
        CHECK ((order_type IN ('LIMIT', 'STOP_LIMIT')) = (limit_price IS NOT NULL)),
    -- A trailing stop's stop is set once the market price is known, after the order is stored.
    CONSTRAINT orders_stop_price_by_type
        CHECK ((order_type IN ('STOP', 'STOP_LIMIT')) = (stop_price IS NOT NULL) OR order_type = 'TRAILING_STOP'),
    CONSTRAINT orders_trail_by_type
        CHECK ((order_type = 'TRAILING_STOP') = (trail_amount IS NOT NULL OR trail_percent IS NOT NULL)
               AND NOT (trail_amount IS NOT NULL AND trail_percent IS NOT NULL)
               AND (trail_peak IS NULL OR order_type = 'TRAILING_STOP'))
);

-- Backs OrderTriggerEngine's scan and the per-account holds on cash and shares.
CREATE INDEX IF NOT EXISTS idx_orders_open
    ON trading.orders (account_id, instrument_id) WHERE status = 'OPEN';

-- Backs the order history's newest-completed-first listing, which sorts by when each order
-- finished rather than when it was submitted (see OrderRepository#findByClientId).
CREATE INDEX IF NOT EXISTS idx_orders_account_completed_at_order_id
    ON trading.orders (account_id, (COALESCE(filled_at, rejected_at, cancelled_at, expired_at, submitted_at)) DESC, order_id DESC);

-- Backs OrderFillMailer's sweep for outcomes not yet emailed.
CREATE INDEX IF NOT EXISTS idx_orders_notice_pending
    ON trading.orders (order_id) WHERE notice_pending;

-- Backs the one-cancels-other lookups: a group's legs, and an exit order's parent.
CREATE INDEX IF NOT EXISTS idx_orders_oco_group
    ON trading.orders (oco_group_id) WHERE oco_group_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_orders_parent
    ON trading.orders (parent_order_id) WHERE parent_order_id IS NOT NULL;

-- Backs OrderRepository's chronological (newest-first) paginated history lookup by account.
CREATE INDEX IF NOT EXISTS idx_orders_account_submitted_at_order_id
    ON trading.orders (account_id, submitted_at DESC, order_id DESC);

-- Order templates: a saved ticket setup (side, type, time in force, percent offsets from the market
-- price, bracket percents) a client applies in one tap. Percent offsets, not prices, so a template
-- fits whatever the price is on the day. At most 20 per client (see OrderTemplateService).
CREATE TABLE IF NOT EXISTS trading.order_templates (
    template_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id UUID NOT NULL
        REFERENCES iam.clients (client_id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 40),
    side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
    order_type TEXT NOT NULL
        CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT', 'TRAILING_STOP')),
    time_in_force TEXT NOT NULL DEFAULT 'DAY' CHECK (time_in_force IN ('DAY', 'GTC')),
    -- Signed percent from the price the side fills at (the ask for a buy, the bid for a sell).
    limit_offset_pct NUMERIC(7,2),
    stop_offset_pct NUMERIC(7,2),
    trail_percent NUMERIC(5,2) CHECK (trail_percent IS NULL OR (trail_percent > 0 AND trail_percent <= 50)),
    -- Bracket, on a buy: percent above and below the entry price. Both or neither.
    take_profit_pct NUMERIC(7,2) CHECK (take_profit_pct IS NULL OR take_profit_pct > 0),
    stop_loss_pct NUMERIC(5,2) CHECK (stop_loss_pct IS NULL OR (stop_loss_pct > 0 AND stop_loss_pct < 100)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT order_templates_bracket_pair CHECK ((take_profit_pct IS NULL) = (stop_loss_pct IS NULL)),
    CONSTRAINT order_templates_name_unique UNIQUE (client_id, name)
);

CREATE TABLE IF NOT EXISTS trading.executions (
    execution_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL
        REFERENCES trading.orders (order_id) ON DELETE RESTRICT,
    fill_quantity BIGINT,
    fill_price NUMERIC(18,6),
    status TEXT NOT NULL CHECK (status IN ('FILLED', 'REJECTED')),
    executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reason TEXT
);

CREATE TABLE IF NOT EXISTS trading.cash_ledger (
    cash_ledger_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL
        REFERENCES trading.accounts (account_id) ON DELETE RESTRICT,
    order_id UUID
        REFERENCES trading.orders (order_id) ON DELETE RESTRICT,
    execution_id UUID
        REFERENCES trading.executions (execution_id) ON DELETE RESTRICT,
    entry_type TEXT NOT NULL
        CHECK (entry_type IN ('DEPOSIT', 'WITHDRAWAL', 'BUY_SETTLEMENT', 'SELL_SETTLEMENT', 'ADJUSTMENT')),
    amount NUMERIC(18,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    description TEXT
);

CREATE TABLE IF NOT EXISTS trading.positions (
    account_id UUID NOT NULL
        REFERENCES trading.accounts (account_id) ON DELETE RESTRICT,
    instrument_id UUID NOT NULL
        REFERENCES trading.instruments (instrument_id) ON DELETE RESTRICT,
    quantity BIGINT NOT NULL DEFAULT 0,
    avg_cost NUMERIC(18,6) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, instrument_id)
);

CREATE TABLE IF NOT EXISTS trading.position_movements (
    movement_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL
        REFERENCES trading.accounts (account_id) ON DELETE RESTRICT,
    instrument_id UUID NOT NULL
        REFERENCES trading.instruments (instrument_id) ON DELETE RESTRICT,
    order_id UUID
        REFERENCES trading.orders (order_id) ON DELETE RESTRICT,
    execution_id UUID
        REFERENCES trading.executions (execution_id) ON DELETE RESTRICT,
    movement_type TEXT NOT NULL
        CHECK (movement_type IN ('BUY_FILL', 'SELL_FILL', 'ADJUSTMENT')),
    quantity_delta BIGINT NOT NULL,
    cost_delta NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Trade Record Archive - Retention: trade records must never be deleted or altered
-- so that audit/compliance data is never lost.
CREATE OR REPLACE FUNCTION trading.reject_delete_or_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Records in % are immutable and cannot be % for retention compliance',
        TG_TABLE_NAME, TG_OP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Orders may transition through statuses (SUBMITTED -> ACCEPTED/REJECTED -> FILLED)
-- but must never be deleted once created.
DROP TRIGGER IF EXISTS trg_orders_no_delete ON trading.orders;
DROP TRIGGER IF EXISTS trg_executions_no_delete_or_update ON trading.executions;
DROP TRIGGER IF EXISTS trg_cash_ledger_no_delete_or_update ON trading.cash_ledger;
DROP TRIGGER IF EXISTS trg_position_movements_no_delete_or_update ON trading.position_movements;
DROP TRIGGER IF EXISTS trg_clients_no_delete ON iam.clients;
DROP TRIGGER IF EXISTS trg_client_profile_no_delete ON iam.client_profile;
DROP TRIGGER IF EXISTS trg_client_credentials_no_delete ON iam.client_credentials;

CREATE TRIGGER trg_orders_no_delete
    BEFORE DELETE ON trading.orders
    FOR EACH ROW EXECUTE FUNCTION trading.reject_delete_or_update();

-- Executions, cash ledger entries, and position movements are append-only audit
-- records and must never be updated or deleted once created.
CREATE TRIGGER trg_executions_no_delete_or_update
    BEFORE UPDATE OR DELETE ON trading.executions
    FOR EACH ROW EXECUTE FUNCTION trading.reject_delete_or_update();

CREATE TRIGGER trg_cash_ledger_no_delete_or_update
    BEFORE UPDATE OR DELETE ON trading.cash_ledger
    FOR EACH ROW EXECUTE FUNCTION trading.reject_delete_or_update();

CREATE TRIGGER trg_position_movements_no_delete_or_update
    BEFORE UPDATE OR DELETE ON trading.position_movements
    FOR EACH ROW EXECUTE FUNCTION trading.reject_delete_or_update();

-- Client identity records must never be deleted (profile fields and status may
-- still be legitimately updated, e.g. address corrections, status transitions).
CREATE TRIGGER trg_clients_no_delete
    BEFORE DELETE ON iam.clients
    FOR EACH ROW EXECUTE FUNCTION trading.reject_delete_or_update();

CREATE TRIGGER trg_client_profile_no_delete
    BEFORE DELETE ON iam.client_profile
    FOR EACH ROW EXECUTE FUNCTION trading.reject_delete_or_update();

CREATE TRIGGER trg_client_credentials_no_delete
    BEFORE DELETE ON iam.client_credentials
    FOR EACH ROW EXECUTE FUNCTION trading.reject_delete_or_update();

-- Market Simulation Backend: owns its own instrument/parameter table (decoupled from
-- trading.instruments) so market-data-app has no cross-schema JPA coupling to account-app or order-app.
CREATE TABLE IF NOT EXISTS marketdata.instruments (
    instrument_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    initial_price NUMERIC(18,6) NOT NULL CHECK (initial_price > 0),
    drift NUMERIC(9,6) NOT NULL,
    volatility NUMERIC(9,6) NOT NULL CHECK (volatility >= 0),
    rng_seed BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- OHLC candles aggregated from the live GBM tick stream (not raw ticks) so table growth
-- stays bounded: one row per instrument per bucket instead of one row per tick.
--
-- bucket_seconds is part of the key because the same instant is legitimately covered by
-- several candles of different widths: the live accumulator writes 60s buckets, while the
-- historical backfill also stores 5m/1h/1d rollups so a year of history costs thousands of
-- rows instead of the ~525k/instrument a 60s-only year would need. Readers always filter on
-- one width, so the widths never mix inside a single series.
--
-- The natural key is the primary key, with no surrogate id: nothing looks a candle up by id,
-- and every read - the history endpoint, the simulation's resume, the backfill's idempotency
-- check - pins the instrument, then the width, then a time range, which this key's order
-- serves directly (scanned backwards for newest-first). One index on the table's hottest
-- insert path instead of three. See scripts/migrate-price-candles-key.sql for databases
-- created before this layout.
CREATE TABLE IF NOT EXISTS marketdata.price_candles (
    instrument_id UUID NOT NULL
        REFERENCES marketdata.instruments (instrument_id) ON DELETE RESTRICT,
    bucket_start TIMESTAMPTZ NOT NULL,
    bucket_seconds INTEGER NOT NULL DEFAULT 60 CHECK (bucket_seconds > 0),
    open NUMERIC(18,6) NOT NULL,
    high NUMERIC(18,6) NOT NULL,
    low NUMERIC(18,6) NOT NULL,
    close NUMERIC(18,6) NOT NULL,
    PRIMARY KEY (instrument_id, bucket_seconds, bucket_start)
);

-- Backs the retention prune, which deletes one width's candles older than a cutoff across every
-- instrument. Without it each hourly prune reads the whole table to find the few rows aged out.
CREATE INDEX IF NOT EXISTS idx_price_candles_width_bucket
    ON marketdata.price_candles (bucket_seconds, bucket_start);

-- Quote Feed Ingestion: one row per parsed+validated quote message accepted from the feed
-- (today, a simulated wire format derived from the GBM tick stream; swappable for a real
-- feed later without changing this table). quote_timestamp is the feed's own timestamp,
-- received_at is when this backend ingested it - kept separate so staleness can be judged
-- against either.
CREATE TABLE IF NOT EXISTS marketdata.quotes (
    quote_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instrument_id UUID NOT NULL
        REFERENCES marketdata.instruments (instrument_id) ON DELETE RESTRICT,
    bid_price NUMERIC(18,6) NOT NULL CHECK (bid_price > 0),
    bid_size BIGINT NOT NULL CHECK (bid_size >= 0),
    ask_price NUMERIC(18,6) NOT NULL CHECK (ask_price > 0),
    ask_size BIGINT NOT NULL CHECK (ask_size >= 0),
    last_price NUMERIC(18,6) NOT NULL CHECK (last_price > 0),
    last_size BIGINT NOT NULL CHECK (last_size >= 0),
    exchange TEXT NOT NULL,
    sequence_number BIGINT NOT NULL,
    quote_timestamp TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (ask_price >= bid_price)
);

-- Backs the "latest quote for a symbol" lookup used by QuoteIngestionService/QuoteController.
CREATE INDEX IF NOT EXISTS idx_quotes_instrument_quote_timestamp
    ON marketdata.quotes (instrument_id, quote_timestamp DESC);