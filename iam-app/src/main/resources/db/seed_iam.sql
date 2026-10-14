-- IAM Schema Seed Data - 10 named clients, plus 40 mock clients for the analyst's reports

-- Insert 10 clients, each registered shortly before its first account opens (seed_trading.sql).
INSERT INTO iam.clients (email, phone, status, created_at) VALUES
    ('alice.johnson@leap.com', '(212) 555-0101', 'ACTIVE', date_trunc('day', NOW()) - INTERVAL '205 days' + TIME '10:00'),
    ('bob.smith@leap.com', '(212) 555-0102', 'ACTIVE', '2025-04-04 10:00:00+00'),
    ('carol.williams@leap.com', '(212) 555-0103', 'ACTIVE', '2025-04-11 10:00:00+00'),
    ('david.brown@leap.com', '(212) 555-0104', 'ACTIVE', '2025-05-02 10:00:00+00'),
    ('emma.davis@leap.com', '(212) 555-0105', 'ACTIVE', '2025-05-09 10:00:00+00'),
    ('frank.miller@leap.com', '(212) 555-0106', 'ACTIVE', '2025-05-16 10:00:00+00'),
    ('grace.wilson@leap.com', '(212) 555-0107', 'LOCKED', '2025-05-30 10:00:00+00'),
    ('henry.taylor@leap.com', '(212) 555-0108', 'ACTIVE', '2025-06-06 10:00:00+00'),
    ('iris.anderson@leap.com', '(212) 555-0109', 'ACTIVE', '2025-06-13 10:00:00+00'),
    ('jack.thomas@leap.com', '(212) 555-0110', 'ACTIVE', '2025-07-04 10:00:00+00')
ON CONFLICT (email) DO NOTHING;

-- Insert client profiles (one per client)
-- LLL-117: address_line_1 renamed to address_line1 to match the client_profile schema fix.
INSERT INTO iam.client_profile (client_id, full_name, date_of_birth, ssn, address_line1, city, state_region, postal_code, country_code, experience_level, initial_deposit_amount)
SELECT c.client_id, 'Alice Johnson', '1985-03-15'::DATE, '123-45-6789', '123 Main Street', 'New York', 'NY', '10001', 'US', 'ADVANCED', 50000.00 FROM iam.clients c WHERE c.email = 'alice.johnson@leap.com'
UNION ALL
SELECT c.client_id, 'Bob Smith', '1990-07-22'::DATE, '123-45-6790', '123 Main Street', 'Los Angeles', 'CA', '10001', 'US', 'INTERMEDIATE', 25000.00 FROM iam.clients c WHERE c.email = 'bob.smith@leap.com'
UNION ALL
SELECT c.client_id, 'Carol Williams', '1988-11-30'::DATE, '123-45-6791', '123 Main Street', 'Chicago', 'IL', '10001', 'US', 'ADVANCED', 100000.00 FROM iam.clients c WHERE c.email = 'carol.williams@leap.com'
UNION ALL
SELECT c.client_id, 'David Brown', '1992-01-10'::DATE, '123-45-6792', '123 Main Street', 'Houston', 'TX', '10001', 'US', 'NOVICE', 5000.00 FROM iam.clients c WHERE c.email = 'david.brown@leap.com'
UNION ALL
SELECT c.client_id, 'Emma Davis', '1987-05-18'::DATE, '123-45-6793', '123 Main Street', 'Phoenix', 'AZ', '10001', 'US', 'INTERMEDIATE', 75000.00 FROM iam.clients c WHERE c.email = 'emma.davis@leap.com'
UNION ALL
SELECT c.client_id, 'Frank Miller', '1991-09-25'::DATE, '123-45-6794', '123 Main Street', 'Philadelphia', 'PA', '10001', 'US', 'ADVANCED', 150000.00 FROM iam.clients c WHERE c.email = 'frank.miller@leap.com'
UNION ALL
SELECT c.client_id, 'Grace Wilson', '1989-02-14'::DATE, '123-45-6795', '123 Main Street', 'San Antonio', 'TX', '10001', 'US', 'NOVICE', 30000.00 FROM iam.clients c WHERE c.email = 'grace.wilson@leap.com'
UNION ALL
SELECT c.client_id, 'Henry Taylor', '1986-08-20'::DATE, '123-45-6796', '123 Main Street', 'San Diego', 'CA', '10001', 'US', 'INTERMEDIATE', 60000.00 FROM iam.clients c WHERE c.email = 'henry.taylor@leap.com'
UNION ALL
SELECT c.client_id, 'Iris Anderson', '1993-12-05'::DATE, '123-45-6797', '123 Main Street', 'Dallas', 'TX', '10001', 'US', 'ADVANCED', 200000.00 FROM iam.clients c WHERE c.email = 'iris.anderson@leap.com'
UNION ALL
SELECT c.client_id, 'Jack Thomas', '1984-06-12'::DATE, '123-45-6798', '123 Main Street', 'San Jose', 'CA', '10001', 'US', 'INTERMEDIATE', 40000.00 FROM iam.clients c WHERE c.email = 'jack.thomas@leap.com'
ON CONFLICT (client_id) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    date_of_birth = EXCLUDED.date_of_birth,
    ssn = EXCLUDED.ssn,
    address_line1 = EXCLUDED.address_line1,
    city = EXCLUDED.city,
    state_region = EXCLUDED.state_region,
    postal_code = EXCLUDED.postal_code,
    country_code = EXCLUDED.country_code,
    experience_level = EXCLUDED.experience_level,
    initial_deposit_amount = EXCLUDED.initial_deposit_amount;

-- Insert client credentials (one per client)
INSERT INTO iam.client_credentials (client_id, password_hash, failed_attempts)
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0 FROM iam.clients c WHERE c.email = 'alice.johnson@leap.com'
UNION ALL
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0 FROM iam.clients c WHERE c.email = 'bob.smith@leap.com'
UNION ALL
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0 FROM iam.clients c WHERE c.email = 'carol.williams@leap.com'
UNION ALL
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0 FROM iam.clients c WHERE c.email = 'david.brown@leap.com'
UNION ALL
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0 FROM iam.clients c WHERE c.email = 'emma.davis@leap.com'
UNION ALL
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0 FROM iam.clients c WHERE c.email = 'frank.miller@leap.com'
UNION ALL
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0 FROM iam.clients c WHERE c.email = 'grace.wilson@leap.com'
UNION ALL
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0 FROM iam.clients c WHERE c.email = 'henry.taylor@leap.com'
UNION ALL
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0 FROM iam.clients c WHERE c.email = 'iris.anderson@leap.com'
UNION ALL
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0 FROM iam.clients c WHERE c.email = 'jack.thomas@leap.com'
ON CONFLICT (client_id) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    failed_attempts = EXCLUDED.failed_attempts;

-- Analytics mock clients: 40 clients registered one every 8 days, 352 to 40 days ago. Client NN
-- has phone (646) 555-02NN, which seed_trading.sql uses to find it. SSNs are in the unissued 900 range.
CREATE TEMP TABLE mock_client AS
SELECT n,
       (ARRAY['Liam', 'Olivia', 'Noah', 'Ava', 'Ethan', 'Sophia', 'Mason', 'Mia', 'Lucas', 'Amelia',
              'Logan', 'Harper', 'Elijah', 'Evelyn', 'James', 'Abigail', 'Benjamin', 'Ella', 'Alexander', 'Scarlett',
              'Daniel', 'Chloe', 'Matthew', 'Lily', 'Samuel', 'Zoe', 'Owen', 'Nora', 'Caleb', 'Hazel',
              'Isaac', 'Aria', 'Nathan', 'Layla', 'Ryan', 'Riley', 'Dylan', 'Stella', 'Leo', 'Maya'])[n] AS first_name,
       (ARRAY['Garcia', 'Martinez', 'Robinson', 'Clark', 'Rodriguez', 'Lewis', 'Lee', 'Walker', 'Hall', 'Allen',
              'Young', 'Hernandez', 'King', 'Wright', 'Lopez', 'Hill', 'Scott', 'Green', 'Adams', 'Baker',
              'Gonzalez', 'Nelson', 'Carter', 'Mitchell', 'Perez', 'Roberts', 'Turner', 'Phillips', 'Campbell', 'Parker',
              'Evans', 'Edwards', 'Collins', 'Stewart', 'Sanchez', 'Morris', 'Rogers', 'Reed', 'Cook', 'Morgan'])[n] AS last_name,
       (ARRAY['New York', 'Boston', 'Chicago', 'Seattle', 'Austin', 'Denver', 'Atlanta', 'Miami', 'Portland', 'Minneapolis'])[1 + n % 10] AS city,
       (ARRAY['NY', 'MA', 'IL', 'WA', 'TX', 'CO', 'GA', 'FL', 'OR', 'MN'])[1 + n % 10] AS state_region,
       (ARRAY['10001', '02108', '60601', '98101', '78701', '80202', '30303', '33131', '97201', '55401'])[1 + n % 10] AS postal_code,
       (ARRAY['NOVICE', 'INTERMEDIATE', 'ADVANCED'])[1 + n % 3] AS experience_level,
       100000.00 + (n % 4) * 50000.00 AS initial_deposit_amount,
       date_trunc('day', NOW()) - make_interval(days => 352 - (n - 1) * 8)
           + make_interval(hours => 9 + n % 8, mins => (n * 13) % 60) AS registered_at
FROM generate_series(1, 40) AS n;

INSERT INTO iam.clients (email, phone, status, created_at)
SELECT lower(first_name || '.' || last_name) || '@leap.com', format('(646) 555-%s', lpad((200 + n)::text, 4, '0')), 'ACTIVE', registered_at
FROM mock_client
ON CONFLICT (email) DO NOTHING;

INSERT INTO iam.client_profile (client_id, full_name, date_of_birth, ssn, address_line1, city, state_region, postal_code, country_code, experience_level, initial_deposit_amount)
SELECT c.client_id, m.first_name || ' ' || m.last_name, DATE '1970-01-01' + (n * 263) % 10000, format('900-20-%s', lpad(n::text, 4, '0')),
       format('%s Market Street', 100 + n), m.city, m.state_region, m.postal_code, 'US', m.experience_level, m.initial_deposit_amount
FROM mock_client m
JOIN iam.clients c ON c.email = lower(m.first_name || '.' || m.last_name) || '@leap.com'
ON CONFLICT (client_id) DO NOTHING;

INSERT INTO iam.client_credentials (client_id, password_hash, failed_attempts)
SELECT c.client_id, crypt('Password123!', gen_salt('bf')), 0
FROM mock_client m
JOIN iam.clients c ON c.email = lower(m.first_name || '.' || m.last_name) || '@leap.com'
ON CONFLICT (client_id) DO NOTHING;

DROP TABLE mock_client;

-- Staff roles: one staff account per role, for local use and tests. Staff emails must not match
-- any client's: sign-in looks clients up first.
INSERT INTO iam.reporting_service_credentials (email, password_hash, role, status)
VALUES
    ('trading.ops@leap.com', crypt('Password123!', gen_salt('bf')), 'TRADING_OPERATIONS', 'ACTIVE'),
    ('commercial.analyst@leap.com', crypt('Password123!', gen_salt('bf')), 'COMMERCIAL_ANALYST', 'ACTIVE')
ON CONFLICT (email) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    role = EXCLUDED.role,
    status = EXCLUDED.status,
    failed_attempts = 0;
