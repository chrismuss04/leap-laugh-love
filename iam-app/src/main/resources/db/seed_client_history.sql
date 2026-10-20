-- Named Clients' Trade History
--
-- A year of everyday trading for the ten named clients in seed_iam.sql, so the portfolio, order
-- history, recap and trading tools all have something to show. Alice is the demo client: the
-- most money, the broadest portfolio and a dozen-plus trades a month. The others vary on purpose -
-- a couple hold one or two stocks, some hold mostly cash, some have not traded this month, and
-- Grace stopped trading when her account was blocked.
--
-- Mounted as 07 (after seed_sp500_trading.sql, before seed_reporting.sql): most of these symbols
-- are S&P 500 instruments, which don't exist yet when seed_trading.sql runs. Like seed_trading.sql
-- it writes orders only; SeededFillService books their fills at market data prices on startup.
--
-- The history is generated rather than listed. A seeded random walk per account trades on
-- weekdays between start_days and end_days ago, sizing buys from market data's starting prices,
-- and tracks what it holds so that every sell is of shares it already owns (SeededFillService
-- can't settle a sell without the holding) and buys stay within the cash deposited so far.
-- Rejected orders change nothing, so they can ask for anything.

-- Alice tops her account up from her bank as the year goes on.
INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, entry_type, amount, currency, created_at, description)
SELECT v.cash_ledger_id::UUID, a.account_id, 'DEPOSIT', v.amount, 'USD',
       date_trunc('day', NOW()) - make_interval(days => v.days_ago) + TIME '08:30', 'Transfer from linked bank account'
FROM (VALUES
    ('dddddddd-dddd-dddd-dddd-eeeeeeee1001', 250, 25000.00),
    ('dddddddd-dddd-dddd-dddd-eeeeeeee1002', 160, 40000.00),
    ('dddddddd-dddd-dddd-dddd-eeeeeeee1003', 75, 50000.00),
    ('dddddddd-dddd-dddd-dddd-eeeeeeee1004', 20, 30000.00)
) AS v (cash_ledger_id, days_ago, amount)
JOIN trading.accounts a ON a.account_number = 'ACC-001-01'
ON CONFLICT (cash_ledger_id) DO NOTHING;

-- One trading profile per account. salt seeds the account's random walk and is the middle of its
-- order ids; trades_per_month is an average over ~21 trading days; a buy spends between ticket_min
-- and ticket_max; sell_bias is the chance a trade sells something held; every reject_every-th
-- order is rejected (0 = never); a quiet account has not traded yet this calendar month; buys
-- leave cash_reserve of the deposits uninvested. Simulated prices drift from the starting prices
-- buys are sized from, so even a fully invested account keeps 20% back to stay out of overdraft.
CREATE TEMP TABLE trade_profile (
    account_number TEXT,
    salt INT,
    universe TEXT[],
    start_days INT,
    end_days INT,
    trades_per_month NUMERIC,
    ticket_min NUMERIC,
    ticket_max NUMERIC,
    sell_bias NUMERIC,
    reject_every INT,
    quiet_this_month BOOLEAN,
    cash_reserve NUMERIC
);

INSERT INTO trade_profile VALUES
    -- Alice: the demo client. Broad, sector-diversified book, very active.
    ('ACC-001-01', 1, ARRAY['AAPL', 'MSFT', 'GOOGL', 'NVDA', 'AMZN', 'META', 'TSLA', 'AVGO', 'JPM', 'V',
                            'MA', 'UNH', 'LLY', 'JNJ', 'COST', 'WMT', 'PG', 'KO', 'XOM', 'CVX',
                            'HD', 'NFLX', 'DIS', 'CAT', 'AMD'],
     330, 1, 15, 4000, 16000, 0.30, 11, FALSE, 0.20),
    -- Bob: a handful of big tech names, small tickets.
    ('ACC-002-01', 2, ARRAY['AAPL', 'MSFT', 'NVDA', 'AMD', 'AMZN'], 300, 1, 5, 1500, 4000, 0.30, 19, FALSE, 0.20),
    -- Carol: diversified across sectors, steady.
    ('ACC-003-01', 3, ARRAY['JPM', 'V', 'UNH', 'LLY', 'PG', 'HD', 'CAT', 'NEE', 'AMT', 'XOM', 'COST'],
     320, 1, 7, 3000, 9000, 0.30, 23, FALSE, 0.20),
    -- David: a novice with two stocks, trades every month or two, nothing yet this month.
    ('ACC-004-01', 4, ARRAY['AAPL', 'KO'], 280, 1, 1.2, 400, 1200, 0.15, 0, TRUE, 0.20),
    -- Emma: income stocks, buy-and-hold, nothing yet this month.
    ('ACC-005-01', 5, ARRAY['JNJ', 'PG', 'KO', 'PEP', 'MCD', 'VZ', 'O'], 300, 1, 2.5, 3000, 8000, 0.20, 0, TRUE, 0.25),
    -- Frank: active trader in energy and banks, high turnover.
    ('ACC-006-01', 6, ARRAY['XOM', 'CVX', 'COP', 'SLB', 'JPM', 'GS', 'BAC', 'MS', 'TSLA', 'NVDA'],
     320, 1, 12, 5000, 15000, 0.45, 13, FALSE, 0.40),
    -- Grace: traded until her account was blocked, five months ago.
    ('ACC-007-01', 7, ARRAY['AAPL', 'DIS', 'SBUX'], 320, 150, 3, 1000, 3000, 0.25, 0, FALSE, 0.50),
    -- Henry: two stocks, sporadic.
    ('ACC-008-01', 8, ARRAY['TSLA', 'NFLX'], 250, 1, 1.5, 2000, 5000, 0.25, 0, FALSE, 0.35),
    -- Iris: large growth-tech book in her main account...
    ('ACC-009-01', 9, ARRAY['NVDA', 'MSFT', 'GOOGL', 'META', 'AMZN', 'CRM', 'ADBE', 'NOW', 'PANW', 'AVGO'],
     310, 1, 6, 8000, 20000, 0.25, 17, FALSE, 0.20),
    -- ...and a second, buy-and-hold account that is mostly cash, with some PayPal and a bitcoin.
    -- The ticket is below one coin, so it buys a whole one when the cash allows.
    ('ACC-009-02', 10, ARRAY['BTC/USD', 'PYPL'], 300, 1, 1, 1000, 3000, 0.00, 0, FALSE, 0.55),
    -- Jack: one stock, mostly cash.
    ('ACC-010-01', 11, ARRAY['TSLA'], 290, 1, 1.5, 2000, 5000, 0.30, 0, FALSE, 0.85);

CREATE TEMP TABLE seed_holding (symbol TEXT PRIMARY KEY, qty BIGINT NOT NULL);

DO $$
DECLARE
    p trade_profile%ROWTYPE;
    v_account UUID;
    v_reasons TEXT[] := ARRAY['Insufficient funds', 'Price moved beyond your price tolerance',
                              'Market quote unavailable', 'Insufficient position quantity'];
    v_days_ago INT;
    v_day TIMESTAMPTZ;
    v_trades INT;
    v_seq INT;
    v_spent NUMERIC;
    v_cash NUMERIC;
    v_at TIMESTAMPTZ;
    v_symbol TEXT;
    v_price NUMERIC;
    v_qty BIGINT;
    v_held BIGINT;
    v_side TEXT;
    v_status TEXT;
    v_reason TEXT;
    v_notional NUMERIC;
    v_tries INT;
BEGIN
    FOR p IN SELECT * FROM trade_profile ORDER BY salt LOOP
        SELECT account_id INTO v_account FROM trading.accounts WHERE account_number = p.account_number;
        -- Generated once: a re-run on another day would lay the same order ids on other dates.
        CONTINUE WHEN v_account IS NULL OR EXISTS (
            SELECT 1 FROM trading.orders
            WHERE order_id::text LIKE format('f0000000-0000-4000-8000-%s%%', lpad(to_hex(p.salt), 4, '0')));

        PERFORM setseed(p.salt / 100.0);
        TRUNCATE seed_holding;
        v_seq := 0;
        -- What seed_trading.sql's own fills for this account cost, so they are paid for too.
        v_spent := (SELECT coalesce(sum(CASE o.side WHEN 'BUY' THEN 1 ELSE -1 END * o.quantity * mi.initial_price), 0)
                    FROM trading.orders o
                    JOIN trading.instruments i ON i.instrument_id = o.instrument_id
                    JOIN marketdata.instruments mi ON mi.symbol = replace(i.symbol, '/', '')
                    WHERE o.account_id = v_account AND o.status = 'FILLED');

        FOR v_days_ago IN REVERSE p.start_days..p.end_days LOOP
            v_day := date_trunc('day', NOW()) - make_interval(days => v_days_ago);
            CONTINUE WHEN extract(isodow FROM v_day) > 5;
            CONTINUE WHEN p.quiet_this_month AND v_day >= date_trunc('month', NOW());

            v_trades := floor(p.trades_per_month / 21 + random());
            FOR t IN 1..v_trades LOOP
                v_seq := v_seq + 1;
                -- Between 13:35 and 19:55 UTC: US market hours.
                v_at := v_day + make_interval(hours => 13, mins => 35 + floor(random() * 380)::int);
                v_cash := (SELECT coalesce(sum(amount), 0) FROM trading.cash_ledger
                           WHERE account_id = v_account AND entry_type = 'DEPOSIT' AND created_at <= v_at)
                          * (1 - p.cash_reserve) - v_spent;

                IF EXISTS (SELECT 1 FROM seed_holding) AND (random() < p.sell_bias OR v_cash < p.ticket_min) THEN
                    v_side := 'SELL';
                    SELECT symbol, qty INTO v_symbol, v_held FROM seed_holding ORDER BY symbol
                    OFFSET floor(random() * (SELECT count(*) FROM seed_holding)) LIMIT 1;
                    -- Close the position about a third of the time, otherwise trim it.
                    v_qty := CASE WHEN random() < 0.3 THEN v_held
                                  ELSE greatest(1, ceil(v_held * (0.2 + random() * 0.5))) END;
                    SELECT initial_price INTO v_price FROM marketdata.instruments WHERE symbol = replace(v_symbol, '/', '');
                ELSE
                    v_side := 'BUY';
                    v_notional := least(p.ticket_min + random() * (p.ticket_max - p.ticket_min), v_cash * 0.8);
                    v_qty := 0;
                    v_tries := 0;
                    -- A share can cost more than the ticket (bitcoin always does), so try another symbol.
                    WHILE v_qty = 0 AND v_tries < 5 LOOP
                        v_symbol := p.universe[1 + floor(random() * array_length(p.universe, 1))::int];
                        SELECT initial_price INTO v_price FROM marketdata.instruments WHERE symbol = replace(v_symbol, '/', '');
                        v_qty := floor(v_notional / v_price);
                        -- One whole unit is fine when the cash covers it.
                        IF v_qty = 0 AND v_price <= v_cash * 0.8 AND random() < 0.5 THEN
                            v_qty := 1;
                        END IF;
                        v_tries := v_tries + 1;
                    END LOOP;
                    CONTINUE WHEN v_qty = 0;
                END IF;

                v_status := 'FILLED';
                v_reason := NULL;
                IF p.reject_every > 0 AND v_seq % p.reject_every = 0 THEN
                    v_status := 'REJECTED';
                    v_reason := v_reasons[1 + (v_seq / p.reject_every) % 4];
                    IF v_reason = 'Insufficient funds' THEN
                        v_side := 'BUY';
                        v_qty := ceil(greatest(v_cash, 1000) * 2 / v_price);
                    ELSIF v_reason = 'Insufficient position quantity' THEN
                        v_side := 'SELL';
                        v_qty := v_qty + coalesce((SELECT qty FROM seed_holding WHERE symbol = v_symbol), 0);
                    END IF;
                END IF;

                INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at,
                                            accepted_at, rejected_at, filled_at, rejection_reason)
                SELECT format('f0000000-0000-4000-8000-%s%s', lpad(to_hex(p.salt), 4, '0'), lpad(to_hex(v_seq), 8, '0'))::UUID,
                       v_account, i.instrument_id, v_side, v_qty, v_status, v_at,
                       CASE WHEN v_status = 'FILLED' THEN v_at + INTERVAL '1 minute' END,
                       CASE WHEN v_status = 'REJECTED' THEN v_at + INTERVAL '1 minute' END,
                       CASE WHEN v_status = 'FILLED' THEN v_at + INTERVAL '2 minutes' END,
                       v_reason
                FROM trading.instruments i WHERE i.symbol = v_symbol
                ON CONFLICT (order_id) DO NOTHING;

                CONTINUE WHEN v_status = 'REJECTED';
                IF v_side = 'BUY' THEN
                    v_spent := v_spent + v_qty * v_price;
                    INSERT INTO seed_holding VALUES (v_symbol, v_qty)
                    ON CONFLICT (symbol) DO UPDATE SET qty = seed_holding.qty + EXCLUDED.qty;
                ELSE
                    v_spent := v_spent - v_qty * v_price;
                    UPDATE seed_holding SET qty = qty - v_qty WHERE symbol = v_symbol;
                    DELETE FROM seed_holding WHERE qty = 0;
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;
END
$$;

DROP TABLE seed_holding;
DROP TABLE trade_profile;

-- Executions for the rejected orders (no fill, so no price needed).
INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at, reason)
SELECT ('eeeeeeee' || substr(o.order_id::text, 9))::UUID, o.order_id, NULL, NULL, o.status, o.rejected_at,
       o.rejection_reason || ' - order rejected'
FROM trading.orders o
WHERE o.order_id::text LIKE 'f0000000-0000-4000-8000-%' AND o.rejected_at IS NOT NULL
ON CONFLICT (execution_id) DO NOTHING;
