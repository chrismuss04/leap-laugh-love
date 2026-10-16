CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS trading;
CREATE SCHEMA IF NOT EXISTS marketdata;
CREATE SCHEMA IF NOT EXISTS reporting;

CREATE TABLE IF NOT EXISTS iam.clients (
    client_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT NOT NULL UNIQUE,
    phone TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('PENDING', 'ACTIVE', 'LOCKED', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Registration: a phone number belongs to one client, like an email or an SSN. Stored in the
-- format iam-app normalizes to, so the same number can't be registered twice in two spellings.
CREATE UNIQUE INDEX IF NOT EXISTS uq_clients_phone
    ON iam.clients (phone);

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
    notify_order_fills BOOLEAN NOT NULL DEFAULT TRUE,
    notify_price_alerts BOOLEAN NOT NULL DEFAULT TRUE,
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

-- Session Timeout & Revocation: one row per login; never store the raw JWT.
CREATE TABLE IF NOT EXISTS iam.client_sessions (
    session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id UUID NOT NULL
        REFERENCES iam.clients (client_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT chk_client_sessions_activity CHECK (last_activity_at >= created_at),
    CONSTRAINT chk_client_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_client_sessions_revocation CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

-- Session Timeout & Revocation: support session lookup by client and expired-session cleanup.
CREATE INDEX IF NOT EXISTS idx_client_sessions_client_id
    ON iam.client_sessions (client_id);
CREATE INDEX IF NOT EXISTS idx_client_sessions_expires_at
    ON iam.client_sessions (expires_at);

-- Password Recovery: one row per emailed reset link. Only a SHA-256 hash of the token is stored,
-- so reading this table does not reveal a usable link.
CREATE TABLE IF NOT EXISTS iam.password_reset_tokens (
    token_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id UUID NOT NULL
        REFERENCES iam.clients (client_id) ON DELETE RESTRICT,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    -- Set when the link resets the password, or when a newer link replaces it.
    used_at TIMESTAMPTZ,
    CONSTRAINT chk_password_reset_tokens_expiry CHECK (expires_at > created_at)
);

-- Password Recovery: find a client's outstanding links when a new one replaces them.
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_client_id
    ON iam.password_reset_tokens (client_id);

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

-- LLL-176
-- Staff credentials for reporting access; separate from customer identities.
CREATE TABLE IF NOT EXISTS iam.reporting_service_credentials (
    service_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL
        CHECK (role IN ('TRADING_OPERATIONS', 'COMMERCIAL_ANALYST')),
    status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('PENDING', 'ACTIVE', 'LOCKED', 'DELETED')),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Staff roles: one row per staff login, kept apart from client_sessions so a staff token can
-- only ever match a staff session (see common's Role and ClientSessionValidator).
-- Same lifetime rules as client_sessions; never store the raw JWT.
CREATE TABLE IF NOT EXISTS iam.staff_sessions (
    session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id UUID NOT NULL
        REFERENCES iam.reporting_service_credentials (service_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT chk_staff_sessions_activity CHECK (last_activity_at >= created_at),
    CONSTRAINT chk_staff_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_staff_sessions_revocation CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX IF NOT EXISTS idx_staff_sessions_staff_id
    ON iam.staff_sessions (staff_id);
CREATE INDEX IF NOT EXISTS idx_staff_sessions_expires_at
    ON iam.staff_sessions (expires_at);

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
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Inactive Accounts: when the account became empty, set by account-app's nightly job.
    inactive_since TIMESTAMPTZ,
    -- When the client was emailed about the current inactive period; NULL until that email is
    -- sent, so a failed send is retried by the next nightly run. Cleared with inactive_since.
    inactive_notified_at TIMESTAMPTZ
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
    reason TEXT,
    -- Trade Reconstruction: the market quote the order was priced from. NULL when none was
    -- used (quote unavailable, seeded fill) or the execution predates these columns.
    quote_bid NUMERIC(18,6),
    quote_ask NUMERIC(18,6),
    quote_last NUMERIC(18,6),
    quote_timestamp TIMESTAMPTZ,
    quote_exchange TEXT
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

-- Trade Reconstruction: who looked up which trades, written before any data is returned.
-- order_id has no foreign key: lookups of orders that don't exist are logged too.
CREATE TABLE IF NOT EXISTS trading.ops_access_log (
    access_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id UUID NOT NULL
        REFERENCES iam.reporting_service_credentials (service_id) ON DELETE RESTRICT,
    action TEXT NOT NULL CHECK (action IN ('SEARCH', 'VIEW_TIMELINE', 'DOWNLOAD_CSV')),
    order_id UUID,
    search_criteria TEXT,
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ops_access_log_order_id
    ON trading.ops_access_log (order_id);

DROP TRIGGER IF EXISTS trg_ops_access_log_no_delete_or_update ON trading.ops_access_log;
CREATE TRIGGER trg_ops_access_log_no_delete_or_update
    BEFORE UPDATE OR DELETE ON trading.ops_access_log
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

-- Order Reporting: one row per completed order, loaded by reporting-etl from the order-events
-- Kafka topic that order-app publishes to once an order is FILLED, or REJECTED after its
-- execution was refused at settlement. A read model for the analyst dashboard, so it has no
-- foreign keys into trading: it is only ever written by the ETL, and replaying the topic must
-- not depend on the trading rows still being there.
CREATE TABLE IF NOT EXISTS reporting.orders (
    order_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    client_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    symbol TEXT NOT NULL,
    side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    status TEXT NOT NULL CHECK (status IN ('FILLED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    order_type TEXT NOT NULL DEFAULT 'MARKET'
        CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT', 'TRAILING_STOP')),
    -- NULL unless the order filled.
    fill_price NUMERIC(18,6) CHECK (fill_price IS NULL OR fill_price > 0),
    rejection_reason TEXT,
    submitted_at TIMESTAMPTZ NOT NULL,
    -- filled_at, rejected_at, cancelled_at or expired_at, by status.
    completed_at TIMESTAMPTZ NOT NULL,
    -- When the ETL loaded the row, kept apart from completed_at so pipeline lag is visible.
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Backs per-client activity (most recent fill) and per-client order reports.
CREATE INDEX IF NOT EXISTS idx_reporting_orders_client_completed_at
    ON reporting.orders (client_id, completed_at);
-- Backs reports over a time range across all clients.
CREATE INDEX IF NOT EXISTS idx_reporting_orders_completed_at
    ON reporting.orders (completed_at);

-- Analyst Reporting: one row per client per UTC month they completed an order in, kept by
-- reporting-etl in the same statement that inserts each new reporting.orders row, so a replayed
-- order never counts twice. Backs the monthly analyst reports (retention, client activity,
-- concentration) without scanning every order. scripts/migrate-reporting-client-months.sql
-- rebuilds it from reporting.orders.
CREATE TABLE IF NOT EXISTS reporting.client_months (
    client_id UUID NOT NULL,
    -- First day of the UTC month the orders completed in.
    month DATE NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    -- Every executed order, FILLED or REJECTED: a client is active in a month they traded or tried
    -- to. CANCELLED and EXPIRED orders never executed and are left out of the rollup.
    order_count INTEGER NOT NULL CHECK (order_count > 0),
    filled_count INTEGER NOT NULL CHECK (filled_count >= 0 AND filled_count <= order_count),
    -- Sum of quantity * fill_price over FILLED orders. Unbounded NUMERIC: a precision limit here
    -- could refuse a sum, and with it the order row loaded in the same statement.
    filled_value NUMERIC NOT NULL CHECK (filled_value >= 0),
    PRIMARY KEY (client_id, month)
);

-- Backs every report over a range of months across all clients.
CREATE INDEX IF NOT EXISTS idx_reporting_client_months_month
    ON reporting.client_months (month);

-- User Reporting: one row per registered client, loaded from client-register events.
-- No IAM foreign key: reporting replay must not depend on operational client rows.
CREATE TABLE IF NOT EXISTS reporting.clients (
    client_id UUID PRIMARY KEY,
    registered_at TIMESTAMPTZ NOT NULL,
    -- ETL arrival time is separate from the original registration time.
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Supports registration counts over a reporting period.
CREATE INDEX IF NOT EXISTS idx_reporting_clients_registered_at
    ON reporting.clients (registered_at);

-- Sector Reference: the GICS sector of each tradable symbol, for the monthly client recap's
-- "Top Sector". Static reference data from seed_reporting_sectors.sql, keyed by the symbol
-- reporting.orders carries. No foreign keys, like the rest of the reporting schema. A symbol with
-- no row here is left out of the sector totals, never guessed.
CREATE TABLE IF NOT EXISTS reporting.instrument_sectors (
    symbol TEXT PRIMARY KEY,
    sector TEXT NOT NULL
);

-- Monthly Client Recaps: one stored recap per client per UTC month, built by reporting-app's
-- monthly job from reporting.orders fills, reporting.instrument_sectors and market data prices, so opening a
-- recap reads one row instead of aggregating. The only reporting table reporting-app writes. Not
-- append-only: a rerun of the job replaces the month's rows.
CREATE TABLE IF NOT EXISTS reporting.client_recaps (
    client_id UUID NOT NULL,
    -- First day of the UTC month the recap covers.
    month DATE NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    -- Version of the payload's JSON shape, so readers can tell an old recap from a new one.
    schema_version SMALLINT NOT NULL CHECK (schema_version > 0),
    payload JSONB NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Also serves "a client's latest recap", scanned backwards by month.
    PRIMARY KEY (client_id, month)
);
