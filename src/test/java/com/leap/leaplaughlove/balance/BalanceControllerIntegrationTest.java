package com.leap.leaplaughlove.balance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/db/balance_transactions_test_setup.sql")
class BalanceControllerIntegrationTest {

    private static final UUID OWNER_CLIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CLIENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OWNER_ACCOUNT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void depositWithValidTokenPersistsLedgerEntry() throws Exception {
        String token = jwtService.generateToken(OWNER_CLIENT_ID, "owner@example.com");

        mockMvc.perform(post("/api/balance/accounts/{accountId}/deposit", OWNER_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", new BigDecimal("25.50"),
                                "description", "Deposit test"))))
                .andExpect(status().isOk());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trading.cash_ledger WHERE account_id = ? AND entry_type = 'DEPOSIT' AND amount = ? AND currency = 'USD' AND description = ?",
                Integer.class,
                OWNER_ACCOUNT_ID,
                new BigDecimal("25.50"),
                "Deposit test");
        assertEquals(1, count);
    }

    @Test
    void withdrawalWithValidTokenPersistsNegativeLedgerAmount() throws Exception {
        String token = jwtService.generateToken(OWNER_CLIENT_ID, "owner@example.com");

        mockMvc.perform(post("/api/balance/accounts/{accountId}/withdrawal", OWNER_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", new BigDecimal("40.00"),
                                "description", "Withdrawal test"))))
                .andExpect(status().isOk());

        BigDecimal amount = jdbcTemplate.queryForObject(
                "SELECT amount FROM trading.cash_ledger WHERE account_id = ? AND entry_type = 'WITHDRAWAL' AND description = ?",
                BigDecimal.class,
                OWNER_ACCOUNT_ID,
                "Withdrawal test");
        assertEquals(new BigDecimal("-40.00"), amount);
    }

    @Test
    void rejectsWithdrawalGreaterThanBalance() throws Exception {
        String token = jwtService.generateToken(OWNER_CLIENT_ID, "owner@example.com");

        mockMvc.perform(post("/api/balance/accounts/{accountId}/withdrawal", OWNER_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", new BigDecimal("1000.00"),
                                "description", "Too large"))))
                .andExpect(status().isBadRequest());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trading.cash_ledger WHERE account_id = ? AND description = ?",
                Integer.class,
                OWNER_ACCOUNT_ID,
                "Too large");
        assertEquals(0, count);
    }

    @Test
    void rejectsWhenTokenUserDoesNotOwnAccount() throws Exception {
        String token = jwtService.generateToken(OTHER_CLIENT_ID, "other@example.com");

        mockMvc.perform(post("/api/balance/accounts/{accountId}/deposit", OWNER_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", new BigDecimal("10.00"),
                                "description", "Forbidden attempt"))))
                .andExpect(status().isForbidden());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trading.cash_ledger WHERE account_id = ? AND description = ?",
                Integer.class,
                OWNER_ACCOUNT_ID,
                "Forbidden attempt");
        assertEquals(0, count);
    }

    @Test
    void rejectsMissingOrInvalidJwt() throws Exception {
        mockMvc.perform(post("/api/balance/accounts/{accountId}/deposit", OWNER_ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                        "amount", new BigDecimal("10.00"),
                                        "description", "No token"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/balance/accounts/{accountId}/deposit", OWNER_ACCOUNT_ID)
                        .header("Authorization", "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", new BigDecimal("10.00"),
                                "description", "Bad token"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsNonNumericOrNegativeAmount() throws Exception {
        String token = jwtService.generateToken(OWNER_CLIENT_ID, "owner@example.com");

        mockMvc.perform(post("/api/balance/accounts/{accountId}/deposit", OWNER_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", "not-a-number",
                                "description", "Bad amount"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/balance/accounts/{accountId}/deposit", OWNER_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", new BigDecimal("-5.00"),
                                "description", "Negative"))))
                .andExpect(status().isBadRequest());
    }
}
