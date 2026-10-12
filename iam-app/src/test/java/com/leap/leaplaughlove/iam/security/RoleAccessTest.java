package com.leap.leaplaughlove.iam.security;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.common.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff roles: iam's client endpoints are for clients only, while every role can keep its own
 * session alive and sign out. Every token here has a live session in its own session table, so
 * the role is the only thing that differs between them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = {"/db/client_registration_test_setup.sql", "/db/client_profile_test_setup.sql"})
@DisplayName("iam-app role access")
class RoleAccessTest {

    private static final UUID CLIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;

    private record Login(String token, UUID sessionId) {}

    /** A signed token for the role, backed by an active session in that role's session table. */
    private Login loginAs(Role role) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiry = now.plusSeconds(3600);
        UUID sessionId = UUID.randomUUID();
        UUID subject = CLIENT_ID;
        if (role.isStaff()) {
            subject = UUID.randomUUID();
            jdbc.update("INSERT INTO iam.reporting_service_credentials (service_id, email, password_hash, role) "
                    + "VALUES (?, ?, 'unused', ?)", subject, subject + "@leap.com", role.name());
        }
        String table = role.isStaff() ? "iam.staff_sessions" : "iam.client_sessions";
        String owner = role.isStaff() ? "staff_id" : "client_id";
        jdbc.update("INSERT INTO " + table + " (session_id, " + owner + ", created_at, last_activity_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?)", sessionId, subject, now.minusSeconds(600).atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC), expiry.atOffset(ZoneOffset.UTC));
        return new Login(jwtService.generateToken(subject, "user@leap.com", role, sessionId, now, expiry), sessionId);
    }

    @Test
    @DisplayName("a client can read its own profile")
    void clientIsAllowed() throws Exception {
        mockMvc.perform(get("/api/iam/v1/clients/me").header("Authorization", "Bearer " + loginAs(Role.CLIENT).token()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TRADING_OPERATIONS", "COMMERCIAL_ANALYST"})
    @DisplayName("staff are refused on client profile endpoints")
    void staffAreForbidden(Role role) throws Exception {
        mockMvc.perform(get("/api/iam/v1/clients/me").header("Authorization", "Bearer " + loginAs(role).token()))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TRADING_OPERATIONS", "COMMERCIAL_ANALYST"})
    @DisplayName("staff keep their own session alive and sign out of it")
    void staffSessionActivityAndLogout(Role role) throws Exception {
        Login login = loginAs(role);
        jdbc.update("UPDATE iam.staff_sessions SET last_activity_at = ? WHERE session_id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5), login.sessionId());
        OffsetDateTime before = activity(login.sessionId());

        mockMvc.perform(post("/api/iam/session/activity").header("Authorization", "Bearer " + login.token()))
                .andExpect(status().isNoContent());
        assertTrue(activity(login.sessionId()).isAfter(before));

        mockMvc.perform(post("/api/iam/session/logout").header("Authorization", "Bearer " + login.token()))
                .andExpect(status().isNoContent());
        assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM iam.staff_sessions WHERE session_id = ?",
                OffsetDateTime.class, login.sessionId()));
        // The signed-out token no longer authenticates anywhere.
        mockMvc.perform(post("/api/iam/session/activity").header("Authorization", "Bearer " + login.token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a staff role can't be claimed against a client session")
    void staffRoleWithClientSessionIsUnauthorized() throws Exception {
        Login client = loginAs(Role.CLIENT);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String forged = jwtService.generateToken(CLIENT_ID, "alice@example.com", Role.TRADING_OPERATIONS,
                client.sessionId(), now, now.plusSeconds(3600));
        mockMvc.perform(post("/api/iam/session/activity").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    private OffsetDateTime activity(UUID sessionId) {
        return jdbc.queryForObject("SELECT last_activity_at FROM iam.staff_sessions WHERE session_id = ?",
                OffsetDateTime.class, sessionId);
    }
}
