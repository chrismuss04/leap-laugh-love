CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS trading;

DROP TABLE IF EXISTS trading.cash_ledger;
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

CREATE TABLE trading.cash_ledger (
    cash_ledger_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    order_id UUID,
    execution_id UUID,
    entry_type VARCHAR(32) NOT NULL
        CHECK (entry_type IN ('DEPOSIT', 'WITHDRAWAL', 'BUY_SETTLEMENT', 'SELL_SETTLEMENT', 'ADJUSTMENT')),
    amount NUMERIC(18,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    description VARCHAR(500),
    FOREIGN KEY (account_id) REFERENCES trading.accounts (account_id)
);

INSERT INTO iam.clients (client_id, email, status)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'owner@example.com', 'ACTIVE'),
    ('22222222-2222-2222-2222-222222222222', 'other@example.com', 'ACTIVE');

INSERT INTO trading.accounts (account_id, client_id, account_number, status, base_currency, trading_enabled, created_at)
VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'ACC-OWNER-USD', 'ACTIVE', 'USD', TRUE, CURRENT_TIMESTAMP),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '22222222-2222-2222-2222-222222222222', 'ACC-OTHER-USD', 'ACTIVE', 'USD', TRUE, CURRENT_TIMESTAMP);

INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, entry_type, amount, currency, created_at, description)
VALUES
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'DEPOSIT', 100.00, 'USD', CURRENT_TIMESTAMP, 'Initial seed balance');
