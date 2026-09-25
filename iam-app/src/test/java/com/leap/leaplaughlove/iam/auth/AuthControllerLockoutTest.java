package com.leap.leaplaughlove.iam.auth;

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

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/db/client_registration_test_setup.sql")
@DisplayName("Auth Controller Lockout Tests")
class AuthControllerLockoutTest {

    private static final String CLIENT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String EMAIL = "bob@example.com";
    private static final String PASSWORD = "CorrectPassword1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void insertClient() {
        jdbcTemplate.update("INSERT INTO iam.clients (client_id, email, status) VALUES (?::uuid, ?, 'ACTIVE')",
                CLIENT_ID, EMAIL);
        jdbcTemplate.update("INSERT INTO iam.client_credentials (client_id, password_hash) VALUES (?::uuid, ?)",
                CLIENT_ID, passwordEncoder.encode(PASSWORD));
    }

    private ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/iam/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    @DisplayName("Failed attempts persist and the 3rd one locks the account with a locked message")
    void thirdFailedAttemptLocksAccount() throws Exception {
        login("wrong").andExpect(status().isUnauthorized());
        login("wrong").andExpect(status().isUnauthorized());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT failed_attempts FROM iam.client_credentials WHERE client_id = ?::uuid", Integer.class, CLIENT_ID));

        login("wrong")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error", is("ACCOUNT_LOCKED")))
                .andExpect(jsonPath("$.message", is("Account is locked due to too many failed login attempts")));
        assertEquals("LOCKED", jdbcTemplate.queryForObject(
                "SELECT status FROM iam.clients WHERE client_id = ?::uuid", String.class, CLIENT_ID));

        // Even the correct password is rejected once locked.
        login(PASSWORD).andExpect(status().isLocked());
    }
}
