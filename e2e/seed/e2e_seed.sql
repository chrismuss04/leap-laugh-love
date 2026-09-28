-- Test users for the Playwright end-to-end suite (e2e/). Loaded after the regular seeds, only
-- into databases the suite runs against - docker-compose.e2e.yml mounts it, and
-- e2e/scripts/seed-local.ps1 applies it to a native Windows database.
--
-- The trading ledgers are append-only, so tests cannot clean up after themselves. Instead every
-- Playwright worker trades from its own account (e2e.trader.NN, picked by parallel index), and
-- assertions compare before/after figures rather than absolute balances.
--
-- Idempotent: fixed ids plus ON CONFLICT DO NOTHING, so re-applying never double-funds an account.
-- SSNs are in the 900 range, which is never issued, so they can't collide with real-looking seeds.

-- ---- Clients -------------------------------------------------------------------------------
INSERT INTO iam.clients (email, phone, status)
SELECT format('e2e.trader.%s@leap.test', lpad(n::text, 2, '0')), '(555) 010-' || lpad(n::text, 4, '0'), 'ACTIVE'
FROM generate_series(0, 15) AS n
UNION ALL SELECT 'e2e.multi@leap.test',   '(555) 020-0001', 'ACTIVE'
UNION ALL SELECT 'e2e.history@leap.test', '(555) 020-0002', 'ACTIVE'
UNION ALL SELECT 'e2e.noaccount@leap.test', '(555) 020-0003', 'ACTIVE'
ON CONFLICT (email) DO NOTHING;

INSERT INTO iam.client_profile (client_id, full_name, date_of_birth, ssn, address_line1, city, state_region,
                                postal_code, country_code, experience_level, initial_deposit_amount)
SELECT c.client_id, p.full_name, '1990-01-01'::DATE, p.ssn, '1 Test Street', 'New York', 'NY', '10001', 'US',
       p.experience_level, 1000000.00
FROM (
    SELECT format('e2e.trader.%s@leap.test', lpad(n::text, 2, '0')) AS email,
           format('E2E Trader %s', lpad(n::text, 2, '0')) AS full_name,
           format('900-00-%s', lpad(n::text, 4, '0')) AS ssn,
           'INTERMEDIATE' AS experience_level
    FROM generate_series(0, 15) AS n
    UNION ALL SELECT 'e2e.multi@leap.test', 'E2E Multi Account', '900-01-0001', 'ADVANCED'
    UNION ALL SELECT 'e2e.history@leap.test', 'E2E History', '900-01-0002', 'NOVICE'
    UNION ALL SELECT 'e2e.noaccount@leap.test', 'E2E No Account', '900-01-0003', 'NOVICE'
) p
JOIN iam.clients c ON c.email = p.email
ON CONFLICT (client_id) DO NOTHING;

INSERT INTO iam.client_credentials (client_id, password_hash, failed_attempts)
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0
FROM iam.clients c
WHERE c.email LIKE 'e2e.%@leap.test'
ON CONFLICT (client_id) DO NOTHING;

-- ---- Accounts ------------------------------------------------------------------------------
-- e2e.noaccount deliberately has none: it covers the "no active account" states.
INSERT INTO trading.accounts (client_id, account_number, status, base_currency, trading_enabled, created_at)
SELECT c.client_id, a.account_number, 'ACTIVE', 'USD', TRUE, NOW() - INTERVAL '30 days'
FROM (
    SELECT format('e2e.trader.%s@leap.test', lpad(n::text, 2, '0')) AS email,
           format('ACC-E2E-%s', lpad(n::text, 2, '0')) AS account_number
    FROM generate_series(0, 15) AS n
    UNION ALL SELECT 'e2e.multi@leap.test', 'ACC-E2E-M1'
    UNION ALL SELECT 'e2e.multi@leap.test', 'ACC-E2E-M2'
    UNION ALL SELECT 'e2e.history@leap.test', 'ACC-E2E-H1'
) a
JOIN iam.clients c ON c.email = a.email
ON CONFLICT (account_number) DO NOTHING;

-- One fixed ledger id per account (md5 of the account number), so a re-run finds it and skips.
INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, entry_type, amount, currency, created_at, description)
SELECT md5('e2e-funding-' || a.account_number)::UUID, a.account_id, 'DEPOSIT', 1000000.00, 'USD', a.created_at,
       'E2E account funding'
FROM trading.accounts a
WHERE a.account_number LIKE 'ACC-E2E-%'
ON CONFLICT (cash_ledger_id) DO NOTHING;

-- ---- Order history fixture -----------------------------------------------------------------
-- 25 rejected orders, one per hour going back from seeding, so the history page has a second
-- page (it shows 20 per page). Rejected, because a filled order would need a priced fill, and
-- SeededFillService books those asynchronously - tests shouldn't wait on it.
INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, rejected_at,
                            rejection_reason)
SELECT md5('e2e-history-order-' || n)::UUID,
       (SELECT account_id FROM trading.accounts WHERE account_number = 'ACC-E2E-H1'),
       (SELECT instrument_id FROM trading.instruments WHERE symbol = CASE WHEN n % 2 = 0 THEN 'AAPL' ELSE 'MSFT' END),
       CASE WHEN n % 3 = 0 THEN 'SELL' ELSE 'BUY' END,
       n,
       'REJECTED',
       NOW() - make_interval(hours => n),
       NOW() - make_interval(hours => n),
       'Insufficient funds - order rejected'
FROM generate_series(1, 25) AS n
ON CONFLICT (order_id) DO NOTHING;
