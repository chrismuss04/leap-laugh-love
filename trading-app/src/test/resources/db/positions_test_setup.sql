CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS trading;

DROP TABLE IF EXISTS trading.positions;
DROP TABLE IF EXISTS trading.position_movements;
DROP TABLE IF EXISTS trading.cash_ledger;
DROP TABLE IF EXISTS trading.executions;
DROP TABLE IF EXISTS trading.orders;
DROP TABLE IF EXISTS trading.instruments;
DROP TABLE IF EXISTS trading.accounts;
DROP TABLE IF EXISTS iam.clients;

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
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (client_id) REFERENCES iam.clients (client_id)
);

CREATE TABLE trading.instruments (
    instrument_id UUID PRIMARY KEY,
    symbol VARCHAR(30) NOT NULL,
    instrument_name VARCHAR(255) NOT NULL,
    asset_class VARCHAR(30) NOT NULL,
    market VARCHAR(30) NOT NULL,
    currency CHAR(3) NOT NULL,
    is_tradable BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE trading.positions (
    account_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    quantity BIGINT NOT NULL,
    avg_cost NUMERIC(18,6) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, instrument_id),
    FOREIGN KEY (account_id) REFERENCES trading.accounts (account_id),
    FOREIGN KEY (instrument_id) REFERENCES trading.instruments (instrument_id)
);

INSERT INTO iam.clients (client_id, email, status)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'owner@example.com', 'ACTIVE'),
    ('22222222-2222-2222-2222-222222222222', 'other@example.com', 'ACTIVE');

INSERT INTO trading.accounts (account_id, client_id, account_number, status, base_currency, trading_enabled, created_at)
VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'ACC-OWNER-USD', 'ACTIVE', 'USD', TRUE, CURRENT_TIMESTAMP),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '22222222-2222-2222-2222-222222222222', 'ACC-OTHER-USD', 'ACTIVE', 'USD', TRUE, CURRENT_TIMESTAMP);

INSERT INTO trading.instruments (instrument_id, symbol, instrument_name, asset_class, market, currency, is_tradable)
VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001', 'AAPL', 'Apple Inc.', 'EQUITY', 'NASDAQ', 'USD', TRUE),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0002', 'MSFT', 'Microsoft Corporation', 'EQUITY', 'NASDAQ', 'USD', TRUE);

INSERT INTO trading.positions (account_id, instrument_id, quantity, avg_cost)
VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001', 25, 183.500000),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0002', 10, 401.250000),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001', 99, 199.990000);
