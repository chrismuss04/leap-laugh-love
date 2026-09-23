-- Runs after positions_test_setup.sql, which creates the clients, accounts, instruments and
-- current positions (owner: 25 AAPL + 10 MSFT; other client: 99 AAPL).
DROP TABLE IF EXISTS trading.position_movements CASCADE;
DROP TABLE IF EXISTS trading.cash_ledger CASCADE;

CREATE TABLE trading.cash_ledger (
    cash_ledger_id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    account_id UUID NOT NULL,
    order_id UUID,
    execution_id UUID,
    entry_type VARCHAR(32) NOT NULL,
    amount NUMERIC(18,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    description VARCHAR(500),
    FOREIGN KEY (account_id) REFERENCES trading.accounts (account_id)
);

CREATE TABLE trading.position_movements (
    movement_id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    account_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    order_id UUID,
    execution_id UUID,
    movement_type VARCHAR(32) NOT NULL,
    quantity_delta BIGINT NOT NULL,
    cost_delta NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES trading.accounts (account_id),
    FOREIGN KEY (instrument_id) REFERENCES trading.instruments (instrument_id)
);

-- Owner: deposited 10,000 two days ago, bought 5 of their 25 AAPL for 800 an hour ago.
INSERT INTO trading.cash_ledger (account_id, entry_type, amount, currency, created_at)
VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'DEPOSIT', 10000.00, 'USD', CURRENT_TIMESTAMP - INTERVAL '2' DAY),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'BUY_SETTLEMENT', -800.00, 'USD', CURRENT_TIMESTAMP - INTERVAL '1' HOUR),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DEPOSIT', 55555.00, 'USD', CURRENT_TIMESTAMP - INTERVAL '1' HOUR);

INSERT INTO trading.position_movements (account_id, instrument_id, movement_type, quantity_delta, cost_delta, created_at)
VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001', 'BUY_FILL', 5, 800.00, CURRENT_TIMESTAMP - INTERVAL '1' HOUR),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001', 99, 1000.00, CURRENT_TIMESTAMP - INTERVAL '1' HOUR);

