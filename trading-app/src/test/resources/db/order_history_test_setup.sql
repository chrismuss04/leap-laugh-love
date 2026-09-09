-- Order History Test Setup
-- Creates tables and test data for order history authorization tests

CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS trading;

-- Create IAM clients table
CREATE TABLE IF NOT EXISTS iam.clients (
    client_id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create trading accounts table
CREATE TABLE IF NOT EXISTS trading.accounts (
    account_id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    account_number VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    base_currency CHAR(3) NOT NULL DEFAULT 'USD',
    trading_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (client_id) REFERENCES iam.clients (client_id) ON DELETE RESTRICT
);

-- Create instruments table
CREATE TABLE IF NOT EXISTS trading.instruments (
    instrument_id UUID PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    asset_class VARCHAR(20) NOT NULL
);

-- Create orders table
CREATE TABLE IF NOT EXISTS trading.orders (
    order_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    side VARCHAR(10) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    type VARCHAR(10) NOT NULL CHECK (type IN ('MARKET', 'LIMIT')),
    quantity NUMERIC(18,6) NOT NULL CHECK (quantity > 0),
    limit_price NUMERIC(18,6),
    status VARCHAR(20) NOT NULL CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'FILLED', 'PENDING')),
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMP WITH TIME ZONE,
    rejected_at TIMESTAMP WITH TIME ZONE,
    filled_at TIMESTAMP WITH TIME ZONE,
    rejection_reason VARCHAR(500),
    FOREIGN KEY (account_id) REFERENCES trading.accounts (account_id) ON DELETE RESTRICT,
    FOREIGN KEY (instrument_id) REFERENCES trading.instruments (instrument_id) ON DELETE RESTRICT
);

-- Create executions table
CREATE TABLE IF NOT EXISTS trading.executions (
    execution_id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    fill_quantity NUMERIC(18,6),
    fill_price NUMERIC(18,6),
    status VARCHAR(20) NOT NULL CHECK (status IN ('FILLED', 'REJECTED')),
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (order_id) REFERENCES trading.orders (order_id) ON DELETE RESTRICT
);

-- Create position movements table
CREATE TABLE IF NOT EXISTS trading.position_movements (
    movement_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    movement_type VARCHAR(20) NOT NULL CHECK (movement_type IN ('BUY_FILL', 'SELL_FILL', 'DIVIDEND', 'STOCK_SPLIT')),
    quantity_delta NUMERIC(18,6) NOT NULL,
    price_per_unit NUMERIC(18,6),
    moved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES trading.accounts (account_id) ON DELETE RESTRICT,
    FOREIGN KEY (instrument_id) REFERENCES trading.instruments (instrument_id) ON DELETE RESTRICT
);

-- Clear existing data
DELETE FROM trading.executions;
DELETE FROM trading.position_movements;
DELETE FROM trading.orders;
DELETE FROM trading.instruments;
DELETE FROM trading.accounts;
DELETE FROM iam.clients;

-- Create index for order history queries
CREATE INDEX IF NOT EXISTS idx_orders_account_submitted_at_order_id
    ON trading.orders (account_id, submitted_at DESC, order_id DESC);

-- Insert test clients (Alice and Bob)
INSERT INTO iam.clients (client_id, email, status)
VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'alice.johnson@leap.com', 'ACTIVE'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bob.smith@leap.com', 'ACTIVE');

-- Insert test accounts (one for Alice, one for Bob)
INSERT INTO trading.accounts (account_id, client_id, account_number, status, base_currency, trading_enabled, created_at)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'ACC-ALICE-1', 'ACTIVE', 'USD', TRUE, CURRENT_TIMESTAMP),
    ('22222222-2222-2222-2222-222222222222', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'ACC-BOB-1', 'ACTIVE', 'USD', TRUE, CURRENT_TIMESTAMP);

-- Insert test instruments
INSERT INTO trading.instruments (instrument_id, symbol, name, asset_class)
VALUES
    ('33333333-3333-3333-3333-333333333333', 'AAPL', 'Apple Inc.', 'EQUITY'),
    ('44444444-4444-4444-4444-444444444444', 'MSFT', 'Microsoft Corp.', 'EQUITY'),
    ('55555555-5555-5555-5555-555555555555', 'GOOGL', 'Alphabet Inc.', 'EQUITY'),
    ('66666666-6666-6666-6666-666666666666', 'TSLA', 'Tesla Inc.', 'EQUITY'),
    ('77777777-7777-7777-7777-777777777777', 'BTC/USD', 'Bitcoin', 'CRYPTO');

-- Insert test orders for Alice
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, type, quantity, limit_price, status, submitted_at, filled_at)
VALUES
    ('cc111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', 'BUY', 'MARKET', 100.0000, NULL, 'FILLED', CURRENT_TIMESTAMP - INTERVAL '10' DAY, CURRENT_TIMESTAMP - INTERVAL '10' DAY),
    ('cc222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', '44444444-4444-4444-4444-444444444444', 'SELL', 'LIMIT', 50.0000, 320.0000, 'FILLED', CURRENT_TIMESTAMP - INTERVAL '5' DAY, CURRENT_TIMESTAMP - INTERVAL '5' DAY),
    ('cc333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', '55555555-5555-5555-5555-555555555555', 'BUY', 'LIMIT', 25.5000, 150.0000, 'PENDING', CURRENT_TIMESTAMP - INTERVAL '1' DAY, NULL);

-- Insert test orders for Bob  
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, type, quantity, limit_price, status, submitted_at, filled_at)
VALUES
    ('dd111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '66666666-6666-6666-6666-666666666666', 'BUY', 'MARKET', 10.0000, NULL, 'FILLED', CURRENT_TIMESTAMP - INTERVAL '7' DAY, CURRENT_TIMESTAMP - INTERVAL '7' DAY),
    ('dd222222-2222-2222-2222-222222222222', '22222222-2222-2222-2222-222222222222', '77777777-7777-7777-7777-777777777777', 'BUY', 'LIMIT', 0.5000, 50000.0000, 'PENDING', CURRENT_TIMESTAMP - INTERVAL '2' DAY, NULL);

-- Insert test executions
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at)
VALUES
    ('ee111111-1111-1111-1111-111111111111', 'cc111111-1111-1111-1111-111111111111', 100.0000, 150.0000, 'FILLED', CURRENT_TIMESTAMP - INTERVAL '10' DAY),
    ('ee222222-2222-2222-2222-222222222222', 'cc222222-2222-2222-2222-222222222222', 50.0000, 320.0000, 'FILLED', CURRENT_TIMESTAMP - INTERVAL '5' DAY),
    ('ee333333-3333-3333-3333-333333333333', 'dd111111-1111-1111-1111-111111111111', 10.0000, 250.0000, 'FILLED', CURRENT_TIMESTAMP - INTERVAL '7' DAY);

-- Insert test position movements
INSERT INTO trading.position_movements (movement_id, account_id, instrument_id, movement_type, quantity_delta, price_per_unit, moved_at)
VALUES
    ('ff111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', 'BUY_FILL', 100.0000, 150.0000, CURRENT_TIMESTAMP - INTERVAL '10' DAY),
    ('ff222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', '44444444-4444-4444-4444-444444444444', 'SELL_FILL', -50.0000, 320.0000, CURRENT_TIMESTAMP - INTERVAL '5' DAY),
    ('ff333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', '66666666-6666-6666-6666-666666666666', 'BUY_FILL', 10.0000, 250.0000, CURRENT_TIMESTAMP - INTERVAL '7' DAY);
