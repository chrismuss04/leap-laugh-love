package com.leap.leaplaughlove.iam.client;

import com.leap.leaplaughlove.common.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
