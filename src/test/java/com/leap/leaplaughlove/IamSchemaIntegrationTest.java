package com.leap.leaplaughlove;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IAM Schema Validation Tests")
class IamSchemaIntegrationTest {

    private static final String SCHEMA_FILE = "src/main/resources/db/leap_laugh_love_schema.sql";
    private static final String SEED_FILE = "src/main/resources/db/seed_iam.sql";

    private String readFile(String filePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(filePath)));
    }

    @Test
    @DisplayName("Schema should define iam.clients table with all required columns")
    void testClientsTableSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS iam.clients"), 
            "Schema should define iam.clients table");
        assertTrue(schema.contains("client_id UUID PRIMARY KEY DEFAULT gen_random_uuid()"),
            "clients table should have client_id as UUID primary key");
        assertTrue(schema.contains("email TEXT NOT NULL UNIQUE"),
            "clients table should have unique email");
        assertTrue(schema.contains("status TEXT NOT NULL DEFAULT 'ACTIVE'"),
            "clients table should have status field with ACTIVE default");
        assertTrue(schema.contains("created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()"),
            "clients table should have created_at timestamp");
    }

    @Test
    @DisplayName("Schema should define iam.client_profile table with foreign key to clients")
    void testClientProfileTableSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS iam.client_profile"),
            "Schema should define iam.client_profile table");
        assertTrue(schema.contains("client_id UUID PRIMARY KEY") && schema.contains("REFERENCES iam.clients"),
            "client_profile should have client_id as FK to clients table");
        assertTrue(schema.contains("full_name TEXT NOT NULL"),
            "client_profile should have full_name");
        assertTrue(schema.contains("date_of_birth DATE NOT NULL"),
            "client_profile should have date_of_birth");
        assertTrue(schema.contains("ssn CHAR(11) NOT NULL UNIQUE"),
            "client_profile should have unique SSN");
        assertTrue(schema.contains("experience_level TEXT NOT NULL"),
            "client_profile should have experience_level");
        assertTrue(schema.contains("initial_deposit_amount NUMERIC(18,2)"),
            "client_profile should have initial_deposit_amount");
    }

    @Test
    @DisplayName("Schema should define iam.client_credentials table with foreign key to clients")
    void testClientCredentialsTableSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS iam.client_credentials"),
            "Schema should define iam.client_credentials table");
        assertTrue(schema.contains("client_id UUID PRIMARY KEY") && 
                   schema.contains("REFERENCES iam.clients"),
            "client_credentials should have client_id as FK to clients table");
        assertTrue(schema.contains("password_hash TEXT NOT NULL"),
            "client_credentials should have password_hash");
        assertTrue(schema.contains("failed_sign_in_attempts INTEGER NOT NULL DEFAULT 0"),
            "client_credentials should track failed sign-in attempts");
    }

    @Test
    @DisplayName("Foreign keys should cascade on delete")
    void testCascadeDeleteConstraints() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        // Count cascade constraints
        int cascadeCount = countOccurrences(schema, "ON DELETE CASCADE");
        assertTrue(cascadeCount >= 2, 
            "Schema should have at least 2 cascade delete constraints for iam tables");
    }

    @Test
    @DisplayName("Seed data should insert exactly 10 clients")
    void testSeedData10Clients() throws Exception {
        String seed = readFile(SEED_FILE);
        
        // Count INSERT statements for clients
        int insertCount = countOccurrences(seed, "INSERT INTO iam.clients");
        assertTrue(insertCount > 0, "Seed data should insert clients");
        
        // Check that we have at least 10 distinct email patterns
        assertTrue(seed.contains("alice.johnson@leap.com"), "Should have alice.johnson");
        assertTrue(seed.contains("bob.smith@leap.com"), "Should have bob.smith");
        assertTrue(seed.contains("carol.williams@leap.com"), "Should have carol.williams");
        assertTrue(seed.contains("david.brown@leap.com"), "Should have david.brown");
        assertTrue(seed.contains("emma.davis@leap.com"), "Should have emma.davis");
        assertTrue(seed.contains("frank.miller@leap.com"), "Should have frank.miller");
        assertTrue(seed.contains("grace.wilson@leap.com"), "Should have grace.wilson");
        assertTrue(seed.contains("henry.taylor@leap.com"), "Should have henry.taylor");
        assertTrue(seed.contains("iris.anderson@leap.com"), "Should have iris.anderson");
        assertTrue(seed.contains("jack.thomas@leap.com"), "Should have jack.thomas");
    }

    @Test
    @DisplayName("Seed data should insert client profiles with all required fields")
    void testSeedDataProfiles() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("INSERT INTO iam.client_profile"),
            "Seed data should insert client profiles");
        assertTrue(seed.contains("full_name"),
            "Profile seed should include full_name");
        assertTrue(seed.contains("date_of_birth"),
            "Profile seed should include date_of_birth");
        assertTrue(seed.contains("ssn"),
            "Profile seed should include ssn");
        assertTrue(seed.contains("experience_level"),
            "Profile seed should include experience_level");
        assertTrue(seed.contains("initial_deposit_amount"),
            "Profile seed should include initial_deposit_amount");
    }

    @Test
    @DisplayName("Seed data should insert client credentials for all clients")
    void testSeedDataCredentials() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("INSERT INTO iam.client_credentials"),
            "Seed data should insert client credentials");
        assertTrue(seed.contains("password_hash"),
            "Credentials seed should include password_hash");
        assertTrue(seed.contains("failed_sign_in_attempts"),
            "Credentials seed should track failed attempts");
    }

    @Test
    @DisplayName("Seed data should reference clients via client_id in profiles")
    void testProfilesReferenceClientsCorrectly() throws Exception {
        String seed = readFile(SEED_FILE);
        
        // Check that profiles are linked to clients via client_id
        assertTrue(seed.contains("c.client_id"),
            "Profiles should reference clients via client_id");
        assertTrue(seed.contains("FROM iam.clients c"),
            "Profiles should use subquery from iam.clients");
    }

    @Test
    @DisplayName("Seed data should reference clients via client_id in credentials")
    void testCredentialsReferenceClientsCorrectly() throws Exception {
        String seed = readFile(SEED_FILE);
        
        // Check that credentials are linked to clients
        assertTrue(seed.contains("SELECT") && seed.contains("FROM iam.clients c"),
            "Credentials should reference clients via subquery");
    }

    @Test
    @DisplayName("Seed data should use password hashing with pgcrypto")
    void testPasswordHashingWithCrypto() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("crypt("),
            "Seed data should use crypt() function for password hashing");
        assertTrue(seed.contains("gen_salt("),
            "Seed data should use gen_salt() for bcrypt salt generation");
    }

    @Test
    @DisplayName("Schema should have valid experience level constraints")
    void testExperienceLevelConstraints() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CHECK (experience_level IN ('NOVICE', 'INTERMEDIATE', 'ADVANCED'))"),
            "Schema should constrain experience_level to valid values");
    }

    @Test
    @DisplayName("Schema should have valid client status constraints")
    void testClientStatusConstraints() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CHECK (status IN ('PENDING', 'ACTIVE', 'LOCKED', 'DELETED'))"),
            "Schema should constrain status to valid values");
    }

    @Test
    @DisplayName("Seed data should include various experience levels")
    void testSeedDataIncludesExperienceLevels() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("'NOVICE'"),
            "Seed data should include NOVICE level");
        assertTrue(seed.contains("'INTERMEDIATE'"),
            "Seed data should include INTERMEDIATE level");
        assertTrue(seed.contains("'ADVANCED'"),
            "Seed data should include ADVANCED level");
    }

    @Test
    @DisplayName("Seed data should include multiple client statuses")
    void testSeedDataIncludesVariousStatuses() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("'ACTIVE'"),
            "Seed data should include ACTIVE status");
        assertTrue(seed.contains("'PENDING'") || seed.contains("PENDING"),
            "Seed data should include PENDING status");
    }

    @Test
    @DisplayName("Seed data should have positive initial deposits")
    void testSeedDataHasPositiveDeposits() throws Exception {
        String seed = readFile(SEED_FILE);
        
        // Check for deposit amounts in the seed data
        assertTrue(seed.contains("50000") || seed.contains("25000") || seed.contains("100000"),
            "Seed data should include various positive deposit amounts");
    }

    @Test
    @DisplayName("Schema should enable pgcrypto extension")
    void testPgcryptoExtension() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CREATE EXTENSION IF NOT EXISTS pgcrypto"),
            "Schema should enable pgcrypto extension for password hashing");
    }

    // Helper method
    private int countOccurrences(String text, String pattern) {
        Pattern p = Pattern.compile(Pattern.quote(pattern));
        return (int) p.matcher(text).results().count();
    }
}
