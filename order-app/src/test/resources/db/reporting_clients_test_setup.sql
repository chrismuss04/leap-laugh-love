CREATE SCHEMA IF NOT EXISTS reporting;

DROP TABLE IF EXISTS reporting.clients;

-- Mirrors reporting.clients in leap_laugh_love_schema.sql.
CREATE TABLE reporting.clients (
    client_id UUID PRIMARY KEY,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    loaded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
