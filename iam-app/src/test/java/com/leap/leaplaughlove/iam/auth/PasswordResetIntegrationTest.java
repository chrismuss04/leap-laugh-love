// Password Recovery: exercise the real endpoints, security filter, token storage and login together.
package com.leap.leaplaughlove.iam.auth;

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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Sql(scripts = "/db/client_registration_test_setup.sql")
@DisplayName("Password Reset Integration Tests")
class PasswordResetIntegrationTest {

    private static final String CLIENT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String EMAIL = "bob@example.com";
    private static final String OLD_PASSWORD = "CorrectPassword1!";
    private static final String NEW_PASSWORD = "BrandNewPassword2!";
    private static final String LINK_PREFIX = "http://localhost:4200/reset-password?token=";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ApplicationEvents events;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void insertClient() {
        jdbcTemplate.update("INSERT INTO iam.clients (client_id, email, status) VALUES (?::uuid, ?, 'ACTIVE')",
                CLIENT_ID, EMAIL);
        jdbcTemplate.update("INSERT INTO iam.client_credentials (client_id, password_hash) VALUES (?::uuid, ?)",
                CLIENT_ID, passwordEncoder.encode(OLD_PASSWORD));
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions login(String password) throws Exception {
        return postJson("/api/iam/auth/login", "{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}");
    }

    private ResultActions forgot(String email) throws Exception {
        return postJson("/api/iam/auth/forgot-password", "{\"email\":\"" + email + "\"}");
    }

    private ResultActions validate(String token) throws Exception {
        return postJson("/api/iam/auth/reset-password/validate", "{\"token\":\"" + token + "\"}");
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        return postJson("/api/iam/auth/reset-password",
                "{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
    }

    private List<String> issuedTokens() {
        return events.stream(PasswordResetRequestedEvent.class)
                .map(event -> event.resetLink().substring(LINK_PREFIX.length()))
                .toList();
    }

    // Requests a link the way the frontend does and returns the token from the link that was issued.
    private String requestToken() throws Exception {
        forgot(EMAIL).andExpect(status().isNoContent());
        List<String> tokens = issuedTokens();
        return tokens.get(tokens.size() - 1);
    }

    private int tokenRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iam.password_reset_tokens", Integer.class);
    }

    @Test
    @DisplayName("A reset link changes the password and can only be used once")
    void resetChangesPasswordAndSpendsTheLink() throws Exception {
        String token = requestToken();
        // Checking the link, as the page does when it opens, does not spend it.
        validate(token).andExpect(status().isNoContent());
        validate(token).andExpect(status().isNoContent());

        reset(token, NEW_PASSWORD).andExpect(status().isNoContent());

        login(NEW_PASSWORD).andExpect(status().isOk());
        login(OLD_PASSWORD).andExpect(status().isUnauthorized());
        validate(token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_RESET_TOKEN")));
        reset(token, "AnotherPassword3!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_RESET_TOKEN")));
        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("The link is addressed to the client and its token is not what the database stores")
    void linkIsDeliveredButOnlyItsHashIsStored() throws Exception {
        String token = requestToken();

        var event = events.stream(PasswordResetRequestedEvent.class).findFirst().orElseThrow();
        assertEquals(EMAIL, event.email());
        assertEquals(LINK_PREFIX + token, event.resetLink());
        var row = jdbcTemplate.queryForMap("SELECT * FROM iam.password_reset_tokens");
        assertEquals(CLIENT_ID, row.get("client_id").toString());
        assertFalse(row.get("token_hash").toString().contains(token));
        assertEquals(event.expiresAt().getEpochSecond(), ((OffsetDateTime) row.get("expires_at")).toEpochSecond());
    }

    @Test
    @DisplayName("An unregistered email gets the same response and no link")
    void unknownEmailLooksTheSameAndIssuesNothing() throws Exception {
        forgot("nobody@example.com").andExpect(status().isNoContent());

        assertEquals(0, tokenRows());
        assertEquals(0, events.stream(PasswordResetRequestedEvent.class).count());
    }

    @Test
    @DisplayName("Requesting a new link stops the earlier one working")
    void newerLinkReplacesEarlierOne() throws Exception {
        String first = requestToken();
        String second = requestToken();

        validate(first).andExpect(status().isBadRequest());
        validate(second).andExpect(status().isNoContent());
        reset(first, NEW_PASSWORD).andExpect(status().isBadRequest());
        login(OLD_PASSWORD).andExpect(status().isOk());
        reset(second, NEW_PASSWORD).andExpect(status().isNoContent());
        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("An expired link is rejected and leaves the password unchanged")
    void expiredLinkIsRejected() throws Exception {
        String token = requestToken();
        jdbcTemplate.update("UPDATE iam.password_reset_tokens SET expires_at = ?",
                OffsetDateTime.now().minusSeconds(1));

        reset(token, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_RESET_TOKEN")));
        login(OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A rejected new password does not spend the link")
    void invalidPasswordLeavesLinkUsable() throws Exception {
        String token = requestToken();

        reset(token, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")));
        reset(token, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("A client locked out by failed logins can sign in again after a reset")
    void resetUnlocksClientLockedByFailedLogins() throws Exception {
        login("wrong").andExpect(status().isUnauthorized());
        login("wrong").andExpect(status().isUnauthorized());
        login("wrong").andExpect(status().isLocked());
        login(OLD_PASSWORD).andExpect(status().isLocked());

        reset(requestToken(), NEW_PASSWORD).andExpect(status().isNoContent());

        login(NEW_PASSWORD).andExpect(status().isOk());
        assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT status FROM iam.clients WHERE client_id = ?::uuid", String.class, CLIENT_ID));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT failed_attempts FROM iam.client_credentials WHERE client_id = ?::uuid", Integer.class, CLIENT_ID));
    }

    @Test
    @DisplayName("A reset signs the client out of every existing session")
    void resetRevokesExistingSessions() throws Exception {
        String body = login(OLD_PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String jwt = mapper.readTree(body).get("accessToken").asText();
        mockMvc.perform(post("/api/iam/session/activity").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());

        reset(requestToken(), NEW_PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/iam/session/activity").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isUnauthorized());
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM iam.client_sessions WHERE client_id = ?::uuid", OffsetDateTime.class, CLIENT_ID));
    }

    @Test
    @DisplayName("Both routes are reachable without signing in, even with a stale token attached")
    void routesArePublic() throws Exception {
        mockMvc.perform(post("/api/iam/auth/forgot-password")
                        .header("Authorization", "Bearer invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isNoContent());
        reset("not-a-real-token", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_RESET_TOKEN")));
    }
}
