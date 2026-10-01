package com.leap.leaplaughlove.iam.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.ClientSessionValidator;
import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.iam.account.AccountClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// LLL-117: Client Registration - Validation user story.
// Confirms registration is rejected when the SSN is missing or the applicant is under 21,
// and still succeeds for a legitimate applicant who just turned 21.
@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/db/client_registration_test_setup.sql")
class ClientRegistrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ClientSessionValidator sessionValidator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private AccountClient accountClient;

    // Builds a registration payload that passes every rule, so each test only has to
    // break the one field it's actually checking.
    private Map<String, Object> validPayload(String email) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", email);
        payload.put("phone", "555-0100");
        payload.put("fullName", "Jordan Rivera");
        payload.put("dateOfBirth", LocalDate.now().minusYears(21).toString());
        payload.put("ssn", "123-45-6789");
        payload.put("addressLine1", "123 Main St");
        payload.put("addressLine2", null);
        payload.put("city", "Springfield");
        payload.put("stateRegion", "IL");
        payload.put("postalCode", "62704");
        payload.put("countryCode", "US");
        payload.put("experienceLevel", "NOVICE");
        payload.put("initialDepositAmount", new BigDecimal("5000.00"));
        payload.put("password", "correct-horse-battery");
        return payload;
    }

    @Test
    void registersClientWhoIsExactly21() throws Exception {
        // Turning 21 today should be enough to pass - it shouldn't require being a day older.
        mockMvc.perform(post("/api/iam/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload("jordan.rivera@example.com"))))
                .andExpect(status().isCreated());
    }

    // Registers and returns the new client's id.
    private UUID register(Map<String, Object> payload) throws Exception {
        String body = mockMvc.perform(post("/api/iam/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("clientId").asText());
    }

    @Test
    void registersClientAsActive() throws Exception {
        UUID clientId = register(validPayload("active.client@example.com"));

        assertEquals("ACTIVE", clientRepository.findById(clientId).orElseThrow().getStatus());
    }

    @Test
    void opensFirstAccountAndDepositsInitialAmountAsTheNewClient() throws Exception {
        UUID accountId = UUID.randomUUID();
        AtomicReference<String> tokenSeen = new AtomicReference<>();
        AtomicReference<String> committedStatusSeen = new AtomicReference<>();
        AtomicReference<Boolean> sessionActiveDuringCall = new AtomicReference<>();
        Map<String, Object> payload = validPayload("funded.client@example.com");
        payload.put("initialDepositAmount", new BigDecimal("7500.00"));

        when(accountClient.createAccount(anyString(), eq("USD"))).thenAnswer(invocation -> {
            tokenSeen.set(invocation.getArgument(0));
            // account-app's JwtAuthenticationFilter applies exactly this check to every client token.
            JwtService.TokenIdentity identity = jwtService.parseIdentity(tokenSeen.get());
            sessionActiveDuringCall.set(sessionValidator.isActive(
                    identity.sessionId(), identity.clientId(), identity.expiresAt()));
            // A second thread has its own connection, so it only sees the client if it has committed.
            committedStatusSeen.set(CompletableFuture.supplyAsync(() -> jdbcTemplate.query(
                    "SELECT status FROM iam.clients WHERE email = ?",
                    (rs, rowNum) -> rs.getString(1), "funded.client@example.com")
                    .stream().findFirst().orElse("NOT VISIBLE")).get(5, TimeUnit.SECONDS));
            return accountId;
        });

        UUID clientId = register(payload);

        assertEquals("ACTIVE", committedStatusSeen.get(),
                "the account must be opened only after the registration has committed");
        assertEquals(clientId, jwtService.parseAndValidate(tokenSeen.get()));
        assertEquals(Boolean.TRUE, sessionActiveDuringCall.get(),
                "account-app rejects tokens that are not backed by an active session");
        verify(accountClient).deposit(eq(tokenSeen.get()), eq(accountId),
                argThat(amount -> amount.compareTo(new BigDecimal("7500.00")) == 0), eq("Initial deposit"));

        JwtService.TokenIdentity identity = jwtService.parseIdentity(tokenSeen.get());
        assertFalse(sessionValidator.isActive(identity.sessionId(), identity.clientId(), identity.expiresAt()),
                "the provisioning session must be revoked once the account is opened and funded");
    }

    @Test
    void registrationStillSucceedsWhenAccountAppIsUnavailable() throws Exception {
        when(accountClient.createAccount(anyString(), anyString()))
                .thenThrow(new ResourceAccessException("account-app unreachable"));

        UUID clientId = register(validPayload("unlucky.client@example.com"));

        assertEquals("ACTIVE", clientRepository.findById(clientId).orElseThrow().getStatus());
        verify(accountClient, never()).deposit(any(), any(), any(), any());
    }

    @Test
    void rejectedRegistrationOpensNoAccount() throws Exception {
        Map<String, Object> payload = validPayload("no.ssn.account@example.com");
        payload.put("ssn", "");

        mockMvc.perform(post("/api/iam/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(accountClient);
    }

    @Test
    void rejectsMissingSsn() throws Exception {
        // We rely on the SSN to identify who someone is, so a blank one should hard-fail registration.
        Map<String, Object> payload = validPayload("no.ssn@example.com");
        payload.put("ssn", "");

        String body = mockMvc.perform(post("/api/iam/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("ssn"));
    }

    @Test
    void rejectsApplicantOneDayShortOf21() throws Exception {
        // The edge case that actually proves the age math is right, not just "is this an adult".
        Map<String, Object> payload = validPayload("almost21@example.com");
        payload.put("dateOfBirth", LocalDate.now().minusYears(21).plusDays(1).toString());

        String body = mockMvc.perform(post("/api/iam/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("dateOfBirth"));
    }

    @Test
    void rejectsMinor() throws Exception {
        // A clearly-underage applicant, well away from the boundary.
        Map<String, Object> payload = validPayload("minor@example.com");
        payload.put("dateOfBirth", LocalDate.now().minusYears(10).toString());

        mockMvc.perform(post("/api/iam/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }
}
