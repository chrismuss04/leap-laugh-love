-- Account names: number each client's accounts 1, 2, ... so they can be shown as "Brokerage 1".
-- Prerequisite: trading.accounts already exists. Safe to rerun after successful application.
-- Fresh databases get the same definition from IAM's db/leap_laugh_love_schema.sql.
-- Run from the repository root in Linux:
-- docker compose exec -T db psql -U paysprint -d paysprint -v ON_ERROR_STOP=1 < scripts/migrate-account-seq.sql
-- Existing accounts are numbered in the order they were opened; new ones get the next number.

BEGIN;

ALTER TABLE trading.accounts
    ADD COLUMN IF NOT EXISTS account_seq INTEGER CHECK (account_seq IS NULL OR account_seq > 0);

-- Number accounts that don't have one yet, after any the client already has.
WITH numbered AS (
    SELECT a.account_id,
           COALESCE((SELECT MAX(n.account_seq) FROM trading.accounts n WHERE n.client_id = a.client_id), 0)
               + ROW_NUMBER() OVER (PARTITION BY a.client_id ORDER BY a.created_at, a.account_number) AS seq
    FROM trading.accounts a
    WHERE a.account_seq IS NULL
)
UPDATE trading.accounts a
SET account_seq = numbered.seq
FROM numbered
WHERE a.account_id = numbered.account_id;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'trading.accounts'::regclass AND conname = 'accounts_client_id_account_seq_key'
    ) THEN
        ALTER TABLE trading.accounts
            ADD CONSTRAINT accounts_client_id_account_seq_key UNIQUE (client_id, account_seq);
    END IF;
END $$;

COMMIT;
