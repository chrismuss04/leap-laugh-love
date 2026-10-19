package com.leap.leaplaughlove.reporting.security;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.common.security.Role;
import com.leap.leaplaughlove.reporting.analyst.AnalystReportService;
import com.leap.leaplaughlove.reporting.recap.RecapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff roles: reports are for commercial analysts only, and monthly recaps for clients only.
 * Every token here has a live session in
 * its own session table, so the role is the only thing that differs between them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("reporting-app role access")
class RoleAccessTest {

    private static final String REPORT = "/api/reporting/analyst/scorecard";
    private static final String RECAP = "/api/reporting/recaps/latest";

    @Autowired private MockMvc mockMvc;
    // The reports' SQL is Postgres-only; here only who may reach them matters.
    @MockBean private AnalystReportService reports;
    @MockBean private RecapService recaps;
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
        UUID subject = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiry = now.plusSeconds(3600);
        jdbc.update("INSERT INTO " + table + " (session_id, " + owner + ", created_at, last_activity_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?)", sessionId, subject, now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC), expiry.atOffset(ZoneOffset.UTC));
        return jwtService.generateToken(subject, role.name().toLowerCase() + "@leap.com", role, sessionId, now, expiry);
    }

    @Test
    @DisplayName("a commercial analyst can read analyst reports")
    void analystIsAllowed() throws Exception {
        mockMvc.perform(get(REPORT).header("Authorization", "Bearer " + tokenFor(Role.COMMERCIAL_ANALYST)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CLIENT", "TRADING_OPERATIONS"})
    @DisplayName("clients and trading operations staff can't read analyst reports")
    void othersAreForbidden(Role role) throws Exception {
        mockMvc.perform(get(REPORT).header("Authorization", "Bearer " + tokenFor(role)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a client can read their monthly recap")
    void clientReadsRecap() throws Exception {
        mockMvc.perform(get(RECAP).header("Authorization", "Bearer " + tokenFor(Role.CLIENT)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"COMMERCIAL_ANALYST", "TRADING_OPERATIONS"})
    @DisplayName("staff can't read monthly recaps")
    void staffCantReadRecaps(Role role) throws Exception {
        mockMvc.perform(get(RECAP).header("Authorization", "Bearer " + tokenFor(role)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a recap needs a token")
    void recapNeedsToken() throws Exception {
        mockMvc.perform(get(RECAP)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an analyst token whose session was revoked is unauthorized")
    void revokedSessionIsUnauthorized() throws Exception {
        String token = tokenFor(Role.COMMERCIAL_ANALYST);
        jdbc.update("UPDATE iam.staff_sessions SET revoked_at = last_activity_at");
        mockMvc.perform(get(REPORT).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("no token is unauthorized")
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get(REPORT)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the health check needs no token")
    void healthIsOpen() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
