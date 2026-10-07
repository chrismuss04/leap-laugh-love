-- LLL-117: minimal iam schema so ClientRegistrationControllerTest can register clients against H2.
CREATE SCHEMA IF NOT EXISTS iam;

-- Session Timeout & Revocation: reset sessions before their parent clients.
DROP TABLE IF EXISTS iam.client_sessions CASCADE;
-- Staff roles: reset staff sessions before their parent staff credentials.
DROP TABLE IF EXISTS iam.staff_sessions CASCADE;
DROP TABLE IF EXISTS iam.reporting_service_credentials CASCADE;
-- Password Recovery: reset tokens before their parent clients.
DROP TABLE IF EXISTS iam.password_reset_tokens CASCADE;

DROP TABLE IF EXISTS iam.client_profile CASCADE;
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
    notify_order_fills BOOLEAN NOT NULL DEFAULT TRUE,
    notify_price_alerts BOOLEAN NOT NULL DEFAULT TRUE,
    -- LLL-117: added DEFAULT CURRENT_TIMESTAMP - Client.java doesn't set client_profile.created_at
    -- itself (it relies on the DB default, same as production's "DEFAULT NOW()"), so without a
    -- default here every insert failed with "NULL not allowed for column CREATED_AT".
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS iam.client_credentials CASCADE;

CREATE TABLE iam.client_credentials (
    client_id UUID PRIMARY KEY REFERENCES iam.clients (client_id),
    password_hash VARCHAR(255) NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Session Timeout & Revocation: support login session creation in integration tests.
CREATE TABLE iam.client_sessions (
    session_id UUID PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES iam.clients (client_id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE
);

-- Staff roles: staff credentials and their sessions, kept apart from clients'.
CREATE TABLE iam.reporting_service_credentials (
    service_id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL CHECK (role IN ('TRADING_OPERATIONS', 'COMMERCIAL_ANALYST')),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE iam.staff_sessions (
    session_id UUID PRIMARY KEY,
    staff_id UUID NOT NULL REFERENCES iam.reporting_service_credentials (service_id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE
);

-- Password Recovery: support reset-link storage in integration tests.
CREATE TABLE iam.password_reset_tokens (
    token_id UUID PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES iam.clients (client_id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE
);
