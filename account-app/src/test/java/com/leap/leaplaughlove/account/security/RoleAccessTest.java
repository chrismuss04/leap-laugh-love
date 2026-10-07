package com.leap.leaplaughlove.account.security;

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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff roles: account-app is for clients only. Every token here has a live session in its own
 * session table, so the role is the only thing that differs between them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/positions_test_setup.sql")
@DisplayName("account-app role access")
class RoleAccessTest {

    private static final UUID CLIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String INTERNAL_VALIDATION =
            "/api/account/internal/accounts/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/validation-data";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;

    /** A signed token for the role, backed by an active session in that role's session table. */
    private String tokenFor(Role role) {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS iam");
        String table = role.isStaff() ? "iam.staff_sessions" : "iam.client_sessions";
        String owner = role.isStaff() ? "staff_id" : "client_id";
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + table + " (session_id UUID PRIMARY KEY, " + owner + " UUID NOT NULL, "
                + "created_at TIMESTAMP WITH TIME ZONE NOT NULL, last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                + "expires_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE)");
        UUID subject = role.isStaff() ? UUID.randomUUID() : CLIENT_ID;
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiry = now.plusSeconds(3600);
        jdbc.update("INSERT INTO " + table + " (session_id, " + owner + ", created_at, last_activity_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?)", sessionId, subject, now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC), expiry.atOffset(ZoneOffset.UTC));
        return jwtService.generateToken(subject, role.name().toLowerCase() + "@leap.com", role, sessionId, now, expiry);
    }

    @Test
    @DisplayName("a client can list its accounts and read internal validation data")
    void clientIsAllowed() throws Exception {
        String token = tokenFor(Role.CLIENT);
        mockMvc.perform(get("/api/account/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get(INTERNAL_VALIDATION).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TRADING_OPERATIONS", "COMMERCIAL_ANALYST"})
    @DisplayName("staff are refused on client and internal account endpoints")
    void staffAreForbidden(Role role) throws Exception {
        String token = tokenFor(role);
        mockMvc.perform(get("/api/account/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(INTERNAL_VALIDATION).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("background settlement tokens still reach the internal endpoints, and nothing else")
    void settlementServiceTokenIsAllowedInternally() throws Exception {
        String token = jwtService.generateSettlementToken(CLIENT_ID);
        int internal = mockMvc.perform(get(INTERNAL_VALIDATION).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertNotEquals(401, internal);
        assertNotEquals(403, internal);
        mockMvc.perform(get("/api/account/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("no token is unauthorized")
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/account/accounts")).andExpect(status().isUnauthorized());
    }
}
