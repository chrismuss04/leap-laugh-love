package com.leap.leaplaughlove.order.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/order_submission_test_setup.sql")
@DisplayName("Order templates")
class OrderTemplateIntegrationTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String URL = "/api/order/templates";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;

    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        ownerToken = sessionToken(OWNER, "owner@example.com");
        otherToken = sessionToken(OTHER, "other@example.com");
    }

    private static final String BRACKET_BUY = "{\"name\":\"%s\",\"side\":\"BUY\",\"orderType\":\"MARKET\","
            + "\"timeInForce\":\"GTC\",\"takeProfitPercent\":20,\"stopLossPercent\":8}";

    @Test
    @DisplayName("saves a setup as percent offsets, and lists it back")
    void savesAndLists() throws Exception {
        save(ownerToken, BRACKET_BUY.formatted("Buy, stop -8%, take-profit +20%"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Buy, stop -8%, take-profit +20%"))
                .andExpect(jsonPath("$.orderType").value("MARKET"))
                .andExpect(jsonPath("$.timeInForce").value("GTC"))
                .andExpect(jsonPath("$.takeProfitPercent").value(20.0))
                .andExpect(jsonPath("$.stopLossPercent").value(8.0));

        mockMvc.perform(get(URL).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].side").value("BUY"));
    }

    @Test
    @DisplayName("each client sees and deletes only their own templates")
    void templatesBelongToTheirOwner() throws Exception {
        String body = save(ownerToken, BRACKET_BUY.formatted("Mine")).andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("templateId").asText();

        mockMvc.perform(get(URL).header("Authorization", "Bearer " + otherToken))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(delete(URL + "/" + id).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(URL + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("two clients can use the same name, but one client can't reuse theirs, in any case")
    void namesAreUniquePerClient() throws Exception {
        save(ownerToken, BRACKET_BUY.formatted("Swing")).andExpect(status().isCreated());
        save(otherToken, BRACKET_BUY.formatted("Swing")).andExpect(status().isCreated());
        save(ownerToken, BRACKET_BUY.formatted("swing")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a client can keep 20 templates, and the 21st is refused")
    void capsTemplatesPerClient() throws Exception {
        for (int i = 0; i < OrderTemplateService.MAX_PER_CLIENT; i++) {
            save(ownerToken, BRACKET_BUY.formatted("Template " + i)).andExpect(status().isCreated());
        }
        save(ownerToken, BRACKET_BUY.formatted("One too many")).andExpect(status().isConflict());
        // Another client is unaffected.
        save(otherToken, BRACKET_BUY.formatted("Fresh")).andExpect(status().isCreated());
        assertEquals(OrderTemplateService.MAX_PER_CLIENT,
                jdbc.queryForObject("SELECT COUNT(*) FROM trading.order_templates WHERE client_id = ?", Integer.class, OWNER));
    }

    @Test
    @DisplayName("offsets must fit the order type, and a bracket needs both percents on a buy")
    void validatesTheSetup() throws Exception {
        save(ownerToken, "{\"name\":\"a\",\"side\":\"BUY\",\"orderType\":\"LIMIT\"}").andExpect(status().isBadRequest());
        save(ownerToken, "{\"name\":\"a\",\"side\":\"BUY\",\"orderType\":\"MARKET\",\"limitOffsetPercent\":-1}")
                .andExpect(status().isBadRequest());
        save(ownerToken, "{\"name\":\"a\",\"side\":\"BUY\",\"orderType\":\"STOP\"}").andExpect(status().isBadRequest());
        save(ownerToken, "{\"name\":\"a\",\"side\":\"SELL\",\"orderType\":\"TRAILING_STOP\"}").andExpect(status().isBadRequest());
        save(ownerToken, "{\"name\":\"a\",\"side\":\"BUY\",\"orderType\":\"MARKET\",\"takeProfitPercent\":10}")
                .andExpect(status().isBadRequest());
        save(ownerToken, "{\"name\":\"a\",\"side\":\"SELL\",\"orderType\":\"MARKET\",\"takeProfitPercent\":10,\"stopLossPercent\":5}")
                .andExpect(status().isBadRequest());
        save(ownerToken, "{\"name\":\"  \",\"side\":\"BUY\",\"orderType\":\"MARKET\"}").andExpect(status().isBadRequest());
        save(ownerToken, "{\"name\":\"" + "x".repeat(41) + "\",\"side\":\"BUY\",\"orderType\":\"MARKET\"}")
                .andExpect(status().isBadRequest());

        save(ownerToken, "{\"name\":\"Stop-limit\",\"side\":\"SELL\",\"orderType\":\"STOP_LIMIT\","
                + "\"stopOffsetPercent\":-5,\"limitOffsetPercent\":-5.5}").andExpect(status().isCreated());
        save(ownerToken, "{\"name\":\"Trail\",\"side\":\"SELL\",\"orderType\":\"TRAILING_STOP\",\"trailPercent\":2.5}")
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("needs a signed-in client")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().is4xxClientError());
    }

    private ResultActions save(String token, String body) throws Exception {
        return mockMvc.perform(post(URL).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String sessionToken(UUID clientId, String email) {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS iam");
        jdbc.execute("CREATE TABLE IF NOT EXISTS iam.client_sessions (session_id UUID PRIMARY KEY, "
                + "client_id UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                + "last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                + "expires_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE)");
        UUID sid = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiry = now.plusSeconds(3600);
        jdbc.update("INSERT INTO iam.client_sessions VALUES (?, ?, ?, ?, ?, NULL)",
                sid, clientId, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), expiry.atOffset(ZoneOffset.UTC));
        return jwtService.generateToken(clientId, email, sid, now, expiry);
    }
}
