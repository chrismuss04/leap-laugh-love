CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS trading;

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
    address_line_1 TEXT NOT NULL,
    address_line_2 TEXT,
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
    failed_sign_in_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
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
        CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'FILLED')),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    accepted_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    filled_at TIMESTAMPTZ,
    rejection_reason TEXT
);

-- Backs OrderRepository's chronological (newest-first) paginated history lookup by account.
CREATE INDEX IF NOT EXISTS idx_orders_account_submitted_at_order_id
    ON trading.orders (account_id, submitted_at DESC, order_id DESC);

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