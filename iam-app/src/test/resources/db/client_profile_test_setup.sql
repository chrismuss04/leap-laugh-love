-- One registered client for ClientProfileControllerTest. Runs after client_registration_test_setup.sql,
-- which (re)creates the iam.clients and iam.client_profile tables.
INSERT INTO iam.clients (client_id, email, phone, status)
VALUES ('11111111-1111-1111-1111-111111111111', 'alice@example.com', '555-0100', 'ACTIVE');

INSERT INTO iam.client_profile (client_id, full_name, date_of_birth, ssn, address_line1, city,
                                postal_code, country_code, experience_level, initial_deposit_amount)
VALUES ('11111111-1111-1111-1111-111111111111', 'Alice Example', '1990-01-01', '111-22-3333',
        '1 Main St', 'Springfield', '62704', 'US', 'INTERMEDIATE', 1000.00);
