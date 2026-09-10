-- LLL-172 - Test Order Placement/Execution
-- Schema-accurate H2 fixture for OrderExecutionPropagationIntegrationTest. Creates the tables
-- involved in the order -> execution -> position ledger -> positions chain and seeds just enough
-- starter data (one client, one account, one instrument) for the test itself to attach orders to.
-- The order/execution/position/position_movements rows are NOT seeded here - the test inserts
-- those itself, since that's the exact behavior this user story is checking.
CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS trading;

-- Dropped in dependency order (most-dependent first) with CASCADE as a safety net, in case
-- another trading-app test sharing this in-memory database already created one of these tables.
DROP TABLE IF EXISTS trading.position_movements CASCADE;
DROP TABLE IF EXISTS trading.positions CASCADE;
DROP TABLE IF EXISTS trading.executions CASCADE;
DROP TABLE IF EXISTS trading.orders CASCADE;
DROP TABLE IF EXISTS trading.instruments CASCADE;
DROP TABLE IF EXISTS trading.accounts CASCADE;
DROP TABLE IF EXISTS iam.clients CASCADE;

CREATE TABLE iam.clients (
    client_id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL
);

CREATE TABLE trading.accounts (
    account_id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    account_number VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    base_currency CHAR(3) NOT NULL,
    trading_enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (client_id) REFERENCES iam.clients (client_id)
);

CREATE TABLE trading.instruments (
    instrument_id UUID PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    instrument_name VARCHAR(255) NOT NULL,
    asset_class VARCHAR(20) NOT NULL CHECK (asset_class IN ('EQUITY', 'FX', 'CRYPTO')),
    market VARCHAR(50) NOT NULL,
    currency CHAR(3) NOT NULL,
    is_tradable BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (symbol, market)
);

CREATE TABLE trading.orders (
    order_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'FILLED')),
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMP WITH TIME ZONE,
    rejected_at TIMESTAMP WITH TIME ZONE,
    filled_at TIMESTAMP WITH TIME ZONE,
    rejection_reason VARCHAR(500),
    FOREIGN KEY (account_id) REFERENCES trading.accounts (account_id),
    FOREIGN KEY (instrument_id) REFERENCES trading.instruments (instrument_id)
);

CREATE TABLE trading.executions (
    execution_id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    fill_quantity BIGINT,
    fill_price NUMERIC(18,6),
    status VARCHAR(20) NOT NULL CHECK (status IN ('FILLED', 'REJECTED')),
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason VARCHAR(500),
    FOREIGN KEY (order_id) REFERENCES trading.orders (order_id)
);

CREATE TABLE trading.positions (
    account_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    quantity BIGINT NOT NULL DEFAULT 0,
    avg_cost NUMERIC(18,6) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, instrument_id),
    FOREIGN KEY (account_id) REFERENCES trading.accounts (account_id),
    FOREIGN KEY (instrument_id) REFERENCES trading.instruments (instrument_id)
);

CREATE TABLE trading.position_movements (
    movement_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    order_id UUID,
    execution_id UUID,
    movement_type VARCHAR(20) NOT NULL CHECK (movement_type IN ('BUY_FILL', 'SELL_FILL', 'ADJUSTMENT')),
    quantity_delta BIGINT NOT NULL,
    cost_delta NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES trading.accounts (account_id),
    FOREIGN KEY (instrument_id) REFERENCES trading.instruments (instrument_id),
    FOREIGN KEY (order_id) REFERENCES trading.orders (order_id),
    FOREIGN KEY (execution_id) REFERENCES trading.executions (execution_id)
);

-- Starter data only: one client, one trading account, one tradable instrument for the
-- test's orders to attach to. No orders/executions/positions/position_movements rows here.
INSERT INTO iam.clients (client_id, email, status)
VALUES ('11111111-1111-1111-1111-111111111111', 'trader@example.com', 'ACTIVE');

INSERT INTO trading.accounts (account_id, client_id, account_number, status, base_currency, trading_enabled)
VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'ACC-TRADER-USD', 'ACTIVE', 'USD', TRUE);

INSERT INTO trading.instruments (instrument_id, symbol, instrument_name, asset_class, market, currency, is_tradable)
VALUES ('dddddddd-dddd-dddd-dddd-dddddddddddd', 'AAPL', 'Apple Inc.', 'EQUITY', 'NASDAQ', 'USD', TRUE);
