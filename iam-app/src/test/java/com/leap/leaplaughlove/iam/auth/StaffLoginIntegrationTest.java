package com.leap.leaplaughlove.iam.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.util.Base64;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Analyst login: staff sign in through the same login endpoint as clients, and get a token with
 * their staff role, backed by a session in iam.staff_sessions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/db/client_registration_test_setup.sql")
@DisplayName("Staff login")
class StaffLoginIntegrationTest {

    private static final String STAFF_ID = "33333333-3333-3333-3333-333333333333";
    private static final String EMAIL = "analyst@leap.com";
    private static final String PASSWORD = "AnalystPass1!";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void insertAnalyst() {
        jdbc.update("INSERT INTO iam.reporting_service_credentials (service_id, email, password_hash, role) "
                + "VALUES (?::uuid, ?, ?, 'COMMERCIAL_ANALYST')", STAFF_ID, EMAIL, passwordEncoder.encode(PASSWORD));
    }

    private ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/iam/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    @DisplayName("an analyst gets a COMMERCIAL_ANALYST token backed by a staff session")
    void analystSignsIn() throws Exception {
        String body = login(PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.role", is("COMMERCIAL_ANALYST")))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(body).get("accessToken").asText();
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));

        assertEquals(STAFF_ID, claims.get("sub").asText());
        assertEquals("COMMERCIAL_ANALYST", claims.get("role").asText());
        var session = jdbc.queryForMap("SELECT * FROM iam.staff_sessions WHERE session_id = ?::uuid",
                claims.get("sid").asText());
        assertEquals(STAFF_ID, session.get("staff_id").toString());
        assertEquals(claims.get("exp").asLong(), ((OffsetDateTime) session.get("expires_at")).toEpochSecond());
        assertNull(session.get("revoked_at"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam.client_sessions", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT last_login_at FROM iam.reporting_service_credentials "
                + "WHERE service_id = ?::uuid", OffsetDateTime.class, STAFF_ID));

        // The token keeps its staff session alive, but can't use client endpoints.
        mockMvc.perform(post("/api/iam/session/activity").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/iam/v1/clients/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // Signing out revokes the staff session, after which the token no longer authenticates.
        mockMvc.perform(post("/api/iam/session/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/iam/session/activity").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("failed staff logins persist, and the 3rd locks the account")
    void thirdFailureLocks() throws Exception {
        login("wrong").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("INVALID_CREDENTIALS")));
        login("wrong").andExpect(status().isUnauthorized());
        login("wrong").andExpect(status().isLocked())
                .andExpect(jsonPath("$.error", is("ACCOUNT_LOCKED")));

        assertEquals("LOCKED", jdbc.queryForObject(
                "SELECT status FROM iam.reporting_service_credentials WHERE service_id = ?::uuid", String.class, STAFF_ID));
        // Locked out even with the right password.
        login(PASSWORD).andExpect(status().isLocked());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM iam.staff_sessions", Integer.class));
    }

    @Test
    @DisplayName("a successful staff login clears earlier failed attempts")
    void successResetsFailures() throws Exception {
        login("wrong").andExpect(status().isUnauthorized());
        login(PASSWORD).andExpect(status().isOk());

        assertEquals(0, jdbc.queryForObject("SELECT failed_attempts FROM iam.reporting_service_credentials "
                + "WHERE service_id = ?::uuid", Integer.class, STAFF_ID));
    }

    @Test
    @DisplayName("a client can't register with a staff member's email")
    void registrationRefusesStaffEmail() throws Exception {
        mockMvc.perform(post("/api/iam/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"analyst@leap.com","phone":"5551234567","fullName":"Ada Lovelace",
                                 "dateOfBirth":"1990-01-01","ssn":"123-45-6789","addressLine1":"1 Main St",
                                 "city":"London","postalCode":"N1","countryCode":"GB","experienceLevel":"INTERMEDIATE",
                                 "initialDepositAmount":5000.00,"password":"password1"}
                                """))
                .andExpect(status().isConflict());
    }
}
