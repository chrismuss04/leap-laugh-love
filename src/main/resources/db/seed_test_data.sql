-- Manual test seed data for the login + balance stories.
-- Test login: email 'jane.doe@example.com' / password 'TestPassword123!'
-- Run with: psql -U postgres -h localhost -d leaplaughlove -f seed_test_data.sql

INSERT INTO iam.clients (client_id, email, phone, status)
VALUES ('11111111-1111-1111-1111-111111111111', 'jane.doe@example.com', '+15550001111', 'ACTIVE')
ON CONFLICT (client_id) DO NOTHING;

INSERT INTO iam.client_credentials (client_id, password_hash, failed_sign_in_attempts, locked_until)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    '$2a$10$Xu53zECFPFTXBmYmGb7ob.tloXRkM9HgErHedUO/5s3idmuj2guKa',
    0,
    NULL
)
ON CONFLICT (client_id) DO NOTHING;

INSERT INTO trading.accounts (account_id, client_id, account_number, status, base_currency, trading_enabled)
VALUES
    ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'ACC-USD-0001', 'ACTIVE', 'USD', TRUE),
    ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 'ACC-EUR-0001', 'ACTIVE', 'EUR', TRUE)
ON CONFLICT (account_id) DO NOTHING;

INSERT INTO trading.cash_ledger (account_id, entry_type, amount, currency, description)
VALUES
    ('22222222-2222-2222-2222-222222222222', 'DEPOSIT', 1000.00, 'USD', 'Initial deposit'),
    ('22222222-2222-2222-2222-222222222222', 'WITHDRAWAL', -150.00, 'USD', 'ATM withdrawal'),
    ('33333333-3333-3333-3333-333333333333', 'DEPOSIT', 500.50, 'EUR', 'Initial deposit');
