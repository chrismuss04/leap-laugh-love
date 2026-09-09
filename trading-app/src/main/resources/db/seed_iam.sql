-- IAM Schema Seed Data - 10 Client Records with Profiles and Credentials

-- Insert 10 clients
INSERT INTO iam.clients (email, phone, status) VALUES
    ('alice.johnson@leap.com', '+1-212-555-0101', 'ACTIVE'),
    ('bob.smith@leap.com', '+1-212-555-0102', 'ACTIVE'),
    ('carol.williams@leap.com', '+1-212-555-0103', 'ACTIVE'),
    ('david.brown@leap.com', '+1-212-555-0104', 'PENDING'),
    ('emma.davis@leap.com', '+1-212-555-0105', 'ACTIVE'),
    ('frank.miller@leap.com', '+1-212-555-0106', 'ACTIVE'),
    ('grace.wilson@leap.com', '+1-212-555-0107', 'LOCKED'),
    ('henry.taylor@leap.com', '+1-212-555-0108', 'ACTIVE'),
    ('iris.anderson@leap.com', '+1-212-555-0109', 'ACTIVE'),
    ('jack.thomas@leap.com', '+1-212-555-0110', 'ACTIVE')
ON CONFLICT (email) DO NOTHING;

-- Insert client profiles (one per client)
INSERT INTO iam.client_profile (client_id, full_name, date_of_birth, ssn, address_line_1, city, state_region, postal_code, country_code, experience_level, initial_deposit_amount)
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
    address_line_1 = EXCLUDED.address_line_1,
    city = EXCLUDED.city,
    state_region = EXCLUDED.state_region,
    postal_code = EXCLUDED.postal_code,
    country_code = EXCLUDED.country_code,
    experience_level = EXCLUDED.experience_level,
    initial_deposit_amount = EXCLUDED.initial_deposit_amount;

-- Insert client credentials (one per client)
INSERT INTO iam.client_credentials (client_id, password_hash, failed_sign_in_attempts)
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
    failed_sign_in_attempts = EXCLUDED.failed_sign_in_attempts;
