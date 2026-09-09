-- LLL-117: minimal iam schema so ClientRegistrationControllerTest can register clients against H2.
CREATE SCHEMA IF NOT EXISTS iam;

DROP TABLE IF EXISTS iam.client_profile;
-- CASCADE defensively, in case another iam-app test suite sharing this in-memory DB already
-- created a table with a foreign key into iam.clients.
DROP TABLE IF EXISTS iam.clients CASCADE;

CREATE TABLE iam.clients (
    client_id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(50),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE iam.client_profile (
    client_id UUID PRIMARY KEY REFERENCES iam.clients (client_id),
    full_name VARCHAR(255) NOT NULL,
    date_of_birth DATE NOT NULL,
    ssn CHAR(11) NOT NULL UNIQUE,
    -- LLL-117: renamed from address_line_1/address_line_2 to address_line1/address_line2 so this
    -- fixture matches Client.java's actual @Column names - the old names caused Jenkins to fail
    -- with "Column ADDRESS_LINE1 not found" since the entity and this fixture disagreed.
    address_line1 VARCHAR(255) NOT NULL,
    address_line2 VARCHAR(255),
    city VARCHAR(100) NOT NULL,
    state_region VARCHAR(100),
    postal_code VARCHAR(20) NOT NULL,
    country_code CHAR(2) NOT NULL,
    experience_level VARCHAR(20) NOT NULL,
    initial_deposit_amount NUMERIC(18,2) NOT NULL,
    -- LLL-117: added DEFAULT CURRENT_TIMESTAMP - Client.java doesn't set client_profile.created_at
    -- itself (it relies on the DB default, same as production's "DEFAULT NOW()"), so without a
    -- default here every insert failed with "NULL not allowed for column CREATED_AT".
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
