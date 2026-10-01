package com.leap.leaplaughlove.iam.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = {"/db/client_registration_test_setup.sql", "/db/client_profile_test_setup.sql"})
@DisplayName("Client Profile Controller Tests")
class ClientProfileControllerTest {

    private static final UUID CLIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    // Session Timeout & Revocation: authenticate against a real stored session.
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String activeToken() {
        UUID sid = UUID.randomUUID();
        var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var expiry = now.plusSeconds(3600);
        jdbc.update("INSERT INTO iam.client_sessions VALUES (?, ?, ?, ?, ?, NULL)",
                sid, CLIENT_ID, now.atOffset(java.time.ZoneOffset.UTC), now.atOffset(java.time.ZoneOffset.UTC),
                expiry.atOffset(java.time.ZoneOffset.UTC));
        return jwtService.generateToken(CLIENT_ID, "alice@example.com", sid, now, expiry);
    }

    @Test
    @DisplayName("GET /me returns the authenticated client's display profile without sensitive fields")
    void returnsOwnProfile() throws Exception {
        String token = activeToken();

        mockMvc.perform(get("/api/iam/v1/clients/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId", is(CLIENT_ID.toString())))
                .andExpect(jsonPath("$.email", is("alice@example.com")))
                .andExpect(jsonPath("$.fullName", is("Alice Example")))
                .andExpect(jsonPath("$.experienceLevel", is("INTERMEDIATE")))
                .andExpect(jsonPath("$.createdAt", startsWith("2024-01-15")))
                .andExpect(jsonPath("$.phone", is("555-0100")))
                .andExpect(jsonPath("$.addressLine1", is("1 Main St")))
                .andExpect(jsonPath("$.countryCode", is("US")))
                .andExpect(jsonPath("$.ssn").doesNotExist())
                .andExpect(jsonPath("$.dateOfBirth").doesNotExist());
    }

    @Test
    @DisplayName("GET /me returns 401 when no valid client session exists")
    void unknownClientIsNotFound() throws Exception {
        String token = jwtService.generateToken(UUID.randomUUID(), "ghost@example.com");

        mockMvc.perform(get("/api/iam/v1/clients/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /me returns 401 without a token")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/api/iam/v1/clients/me"))
                .andExpect(status().isUnauthorized());
    }

    // Session Timeout & Revocation: real database updates take effect on the next request.
    @Test
    void revokedSessionIsRejected() throws Exception {
        String token = activeToken();
        jdbc.update("UPDATE iam.client_sessions SET revoked_at = CURRENT_TIMESTAMP");
        mockMvc.perform(get("/api/iam/v1/clients/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void idleSessionIsRejected() throws Exception {
        String token = activeToken();
        jdbc.update("UPDATE iam.client_sessions SET last_activity_at = ?",
                java.time.OffsetDateTime.now().minusMinutes(10));
        mockMvc.perform(get("/api/iam/v1/clients/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Autowired private PasswordEncoder passwordEncoder;

    private void givenPassword(String password) {
        jdbc.update("INSERT INTO iam.client_credentials (client_id, password_hash, failed_attempts) VALUES (?, ?, 0)",
                CLIENT_ID, passwordEncoder.encode(password));
    }

    @Autowired private ObjectMapper objectMapper;

    /** Sends Alice's current settings with the given fields changed. */
    private ResultActions updateSettings(Map<String, Object> changes) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "fullName", "Alice Example", "email", "alice@example.com", "phone", "555-0100",
                "addressLine1", "1 Main St", "city", "Springfield", "postalCode", "62704", "countryCode", "US",
                "notifyOrderFills", true, "notifyPriceAlerts", true));
        body.putAll(changes);
        return mockMvc.perform(put("/api/iam/v1/clients/me").header("Authorization", "Bearer " + activeToken())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    @Test
    @DisplayName("PUT /me updates name, phone, address and notification preferences without a password")
    void updatesProfileWithoutPassword() throws Exception {
        updateSettings(Map.of("fullName", "Alice Updated", "phone", "2125550101", "addressLine1", "2 Oak Ave",
                "addressLine2", "Apt 4", "stateRegion", "NY", "city", "New York", "postalCode", "10001",
                "notifyOrderFills", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName", is("Alice Updated")))
                .andExpect(jsonPath("$.phone", is("(212) 555-0101")))
                .andExpect(jsonPath("$.addressLine2", is("Apt 4")))
                .andExpect(jsonPath("$.notifyOrderFills", is(false)))
                .andExpect(jsonPath("$.notifyPriceAlerts", is(true)));

        mockMvc.perform(get("/api/iam/v1/clients/me").header("Authorization", "Bearer " + activeToken()))
                .andExpect(jsonPath("$.fullName", is("Alice Updated")))
                .andExpect(jsonPath("$.addressLine1", is("2 Oak Ave")))
                .andExpect(jsonPath("$.city", is("New York")))
                .andExpect(jsonPath("$.stateRegion", is("NY")))
                .andExpect(jsonPath("$.postalCode", is("10001")))
                .andExpect(jsonPath("$.notifyOrderFills", is(false)));
    }

    @Test
    @DisplayName("PUT /me changes the email and password when the current password is correct")
    void changesEmailAndPasswordWithCurrentPassword() throws Exception {
        givenPassword("OldPassword1!");

        updateSettings(Map.of("email", "alice.new@example.com",
                "currentPassword", "OldPassword1!", "newPassword", "NewPassword1!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("alice.new@example.com")));

        String hash = jdbc.queryForObject("SELECT password_hash FROM iam.client_credentials WHERE client_id = ?",
                String.class, CLIENT_ID);
        assertTrue(passwordEncoder.matches("NewPassword1!", hash));
    }

    @Test
    @DisplayName("PUT /me rejects an email change with a wrong current password")
    void rejectsWrongCurrentPassword() throws Exception {
        givenPassword("OldPassword1!");

        updateSettings(Map.of("email", "alice.new@example.com", "currentPassword", "WrongPassword1!"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /me rejects an email that another client already uses")
    void rejectsTakenEmail() throws Exception {
        givenPassword("OldPassword1!");
        jdbc.update("INSERT INTO iam.clients (client_id, email, status) VALUES (?, 'bob@example.com', 'ACTIVE')",
                UUID.randomUUID());

        updateSettings(Map.of("email", "bob@example.com", "currentPassword", "OldPassword1!"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PUT /me validates the new settings before saving")
    void rejectsInvalidSettings() throws Exception {
        updateSettings(Map.of("fullName", "", "email", "not-an-email", "addressLine1", "", "countryCode", "USA",
                "currentPassword", "OldPassword1!", "newPassword", "short"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/iam/v1/clients/me").header("Authorization", "Bearer " + activeToken()))
                .andExpect(jsonPath("$.fullName", is("Alice Example")))
                .andExpect(jsonPath("$.addressLine1", is("1 Main St")));
    }
}
