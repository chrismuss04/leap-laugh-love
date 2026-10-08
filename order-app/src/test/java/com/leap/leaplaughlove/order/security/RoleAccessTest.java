package com.leap.leaplaughlove.order.security;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.common.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff roles: order-app is for clients only, except trade reconstruction (Trading Operations
 * only). Every token here has a live session in its own session table, so the role is the only
 * thing that differs between them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("order-app role access")
class RoleAccessTest {

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
    @DisplayName("a client's order reaches the controller (rejected as invalid, not as unauthorised)")
    void clientIsAllowed() throws Exception {
        mockMvc.perform(post("/api/order/orders").header("Authorization", "Bearer " + tokenFor(Role.CLIENT))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TRADING_OPERATIONS", "COMMERCIAL_ANALYST"})
    @DisplayName("staff can't place orders or read order history")
    void staffAreForbidden(Role role) throws Exception {
        String token = tokenFor(role);
        mockMvc.perform(post("/api/order/orders").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/order/orders/history").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("no token is unauthorized")
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/order/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
    @Test
    @Sql("/db/order_submission_test_setup.sql")
    @DisplayName("Trading Operations can search trades, view a timeline and download it")
    void tradingOperationsCanReconstructTrades() throws Exception {
        String token = tokenFor(Role.TRADING_OPERATIONS);
        mockMvc.perform(get("/api/order/ops/orders").header("Authorization", "Bearer " + token).param("symbol", "AAPL"))
                .andExpect(status().isOk());
        // 404, not 403: the role got through.
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(get("/api/order/ops/orders/" + unknown + "/timeline").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/order/ops/orders/" + unknown + "/timeline.csv").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CLIENT", "COMMERCIAL_ANALYST"})
    @DisplayName("clients and commercial analysts can't reconstruct trades")
    void othersCannotReconstructTrades(Role role) throws Exception {
        String token = tokenFor(role);
        UUID orderId = UUID.randomUUID();
        mockMvc.perform(get("/api/order/ops/orders").header("Authorization", "Bearer " + token).param("symbol", "AAPL"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/order/ops/orders/" + orderId + "/timeline").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/order/ops/orders/" + orderId + "/timeline.csv").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("trade reconstruction without a token is unauthorized")
    void tradeReconstructionWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/order/ops/orders").param("symbol", "AAPL"))
                .andExpect(status().isUnauthorized());
    }
}
