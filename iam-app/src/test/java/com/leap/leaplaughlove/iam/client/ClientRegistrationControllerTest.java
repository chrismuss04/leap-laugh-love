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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
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
//
// Registration: an application only becomes a client once the emailed link is used, and an
// application whose email, phone number or SSN is already registered is answered exactly like one
// that isn't - only the emails differ.
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

    @MockBean
    private JavaMailSender mailSender;

    private static final String LINK_PREFIX = "http://localhost:4200/verify-email?token=";
    private static final String APPLICATION_SUBJECT = "Your Leapfolio account application";
    private static final String DETAILS_REUSED_SUBJECT = "Someone applied for a Leapfolio account with your details";

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
    void acceptsClientWhoIsExactly21() throws Exception {
        // Turning 21 today should be enough to pass - it shouldn't require being a day older.
        mockMvc.perform(post("/api/iam/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload("jordan.rivera@example.com"))))
                .andExpect(status().isAccepted());
    }

    // Submits an application and returns the response body, which must never depend on the outcome.
    private String submit(Map<String, Object> payload) throws Exception {
        return mockMvc.perform(post("/api/iam/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    // Emails are sent off the request thread, so each is waited for. Looked up by recipient and
    // subject rather than by count, since every test uses its own addresses.
    private SimpleMailMessage awaitMail(String to, String subject) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            Optional<SimpleMailMessage> match = mailsTo(to).stream()
                    .filter(message -> subject.equals(message.getSubject())).findFirst();
            if (match.isPresent() || System.currentTimeMillis() > deadline) {
                return match.orElseThrow(() -> new AssertionError("no \"" + subject + "\" email to " + to));
            }
            Thread.sleep(20);
        }
    }

    private List<SimpleMailMessage> mailsTo(String to) {
        // Copied first: the sending thread may still be adding to it.
        return List.copyOf(mockingDetails(mailSender).getInvocations()).stream()
                .map(invocation -> invocation.getArgument(0, SimpleMailMessage.class))
                .filter(message -> List.of(message.getTo()).contains(to))
                .toList();
    }

    // The newest confirmation link emailed to this address.
    private String awaitToken(String email) throws InterruptedException {
        awaitMail(email, "Confirm your email to open your Leapfolio account");
        List<SimpleMailMessage> mails = mailsTo(email);
        String text = mails.get(mails.size() - 1).getText();
        return text.substring(text.indexOf(LINK_PREFIX) + LINK_PREFIX.length()).split("\\s", 2)[0];
    }

    private org.springframework.test.web.servlet.ResultActions useLink(String token) throws Exception {
        return mockMvc.perform(post("/api/iam/v1/clients/register/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", token))));
    }

    // Applies, confirms the email with the link that was sent, and returns the new client's id.
    private UUID register(Map<String, Object> payload) throws Exception {
        String email = (String) payload.get("email");
        submit(payload);
        useLink(awaitToken(email)).andExpect(status().isNoContent());
        return clientRepository.findByEmail(email).orElseThrow().getClientId();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iam." + table, Integer.class);
    }

    @Test
    void anApplicationIsNotAClientUntilItsLinkIsUsed() throws Exception {
        String body = submit(validPayload("waiting.client@example.com"));

        assertEquals("Thanks for applying. Check your email for the next step.",
                objectMapper.readTree(body).get("message").asText());
        assertEquals(1, objectMapper.readTree(body).size(), "the response must say nothing about the applicant");
        awaitToken("waiting.client@example.com");
        assertFalse(clientRepository.existsByEmail("waiting.client@example.com"));
        assertEquals(0, count("client_credentials"));
        verifyNoInteractions(accountClient);
    }

    @Test
    void theLinkStoresOnlyAHashOfItsToken() throws Exception {
        submit(validPayload("hashed.token@example.com"));
        String token = awaitToken("hashed.token@example.com");

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM iam.pending_registrations WHERE token_hash = ?", Integer.class, token));
        assertEquals(1, count("pending_registrations"));
    }

    @Test
    void aLinkWorksOnce() throws Exception {
        submit(validPayload("once.client@example.com"));
        String token = awaitToken("once.client@example.com");

        useLink(token).andExpect(status().isNoContent());
        useLink(token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_VERIFICATION_LINK"));

        assertEquals(1, count("clients"));
        assertEquals(0, count("pending_registrations"), "a confirmed application must not be kept");
    }

    @Test
    void anUnknownLinkIsRefused() throws Exception {
        useLink("not-a-token").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_VERIFICATION_LINK"));
    }

    @Test
    void anExpiredLinkIsRefusedAndItsApplicationDiscarded() throws Exception {
        submit(validPayload("late.client@example.com"));
        String token = awaitToken("late.client@example.com");
        jdbcTemplate.update("UPDATE iam.pending_registrations SET created_at = ?, expires_at = ?",
                java.time.OffsetDateTime.now().minusDays(2), java.time.OffsetDateTime.now().minusDays(1));

        useLink(token).andExpect(status().isBadRequest());
        assertEquals(0, count("clients"));

        // The next application, from anyone, clears out what has expired.
        Map<String, Object> other = validPayload("other.client@example.com");
        other.put("ssn", "987-65-4321");
        other.put("phone", "555-0199");
        submit(other);
        assertEquals(1, count("pending_registrations"));
    }

    @Test
    void applyingAgainReplacesTheEarlierLink() throws Exception {
        submit(validPayload("twice.client@example.com"));
        String first = awaitToken("twice.client@example.com");
        submit(validPayload("twice.client@example.com"));
        long deadline = System.currentTimeMillis() + 5000;
        while (mailsTo("twice.client@example.com").size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        String second = awaitToken("twice.client@example.com");

        useLink(first).andExpect(status().isBadRequest());
        useLink(second).andExpect(status().isNoContent());
    }

    @Test
    void aRegisteredEmailIsAnsweredLikeANewOneAndItsOwnerIsEmailed() throws Exception {
        String fresh = submit(validPayload("taken.email@example.com"));
        useLink(awaitToken("taken.email@example.com")).andExpect(status().isNoContent());

        Map<String, Object> again = validPayload("taken.email@example.com");
        again.put("ssn", "987-65-4321");
        again.put("phone", "555-0199");
        String duplicate = submit(again);

        assertEquals(fresh, duplicate);
        assertThat(awaitMail("taken.email@example.com", APPLICATION_SUBJECT).getText())
                .contains("already registered").doesNotContain("http");
        assertEquals(1, count("clients"));
        assertEquals(0, count("pending_registrations"));
    }

    @Test
    void aRegisteredSsnIsAnsweredLikeANewOneAndBothPartiesAreEmailed() throws Exception {
        String fresh = submit(validPayload("ssn.owner@example.com"));
        useLink(awaitToken("ssn.owner@example.com")).andExpect(status().isNoContent());

        Map<String, Object> reuse = validPayload("ssn.reuser@example.com");
        reuse.put("phone", "555-0199");
        String duplicate = submit(reuse);

        assertEquals(fresh, duplicate);
        assertThat(awaitMail("ssn.reuser@example.com", APPLICATION_SUBJECT).getText())
                .contains("could not open").doesNotContain("http");
        // Only the owner is told which detail it was, and never the number itself.
        assertThat(awaitMail("ssn.owner@example.com", DETAILS_REUSED_SUBJECT).getText())
                .contains("the Social Security Number on your account").doesNotContain("123-45-6789");
        assertThat(awaitMail("ssn.reuser@example.com", APPLICATION_SUBJECT).getText())
                .doesNotContainIgnoringCase("social security");
        assertEquals(1, count("clients"));
        assertEquals(0, count("pending_registrations"));
    }

    @Test
    void aRegisteredPhoneNumberIsAnsweredLikeANewOneAndBothPartiesAreEmailed() throws Exception {
        Map<String, Object> owner = validPayload("phone.owner@example.com");
        owner.put("phone", "(212) 555-0142");
        String fresh = submit(owner);
        useLink(awaitToken("phone.owner@example.com")).andExpect(status().isNoContent());

        // The same number typed another way.
        Map<String, Object> reuse = validPayload("phone.reuser@example.com");
        reuse.put("ssn", "987-65-4321");
        reuse.put("phone", "+1 212.555.0142");
        String duplicate = submit(reuse);

        assertEquals(fresh, duplicate);
        assertThat(awaitMail("phone.reuser@example.com", APPLICATION_SUBJECT).getText()).contains("could not open");
        assertThat(awaitMail("phone.owner@example.com", DETAILS_REUSED_SUBJECT).getText())
                .contains("the phone number on your account").doesNotContain("555-0142");
        assertEquals(1, count("clients"));
        assertEquals(0, count("pending_registrations"));
    }

    @Test
    void aClientWhoseEmailPhoneNumberAndSsnAreAllReusedGetsOneEmailListingThem() throws Exception {
        Map<String, Object> owner = validPayload("all.three@example.com");
        owner.put("phone", "(212) 555-0142");
        String fresh = submit(owner);
        useLink(awaitToken("all.three@example.com")).andExpect(status().isNoContent());

        String duplicate = submit(owner);

        assertEquals(fresh, duplicate);
        assertThat(awaitMail("all.three@example.com", DETAILS_REUSED_SUBJECT).getText())
                .contains("using the email address, the phone number and the Social Security Number on your account")
                .contains("No new account was opened");
        Thread.sleep(300);
        assertThat(mailsTo("all.three@example.com")).noneMatch(m -> APPLICATION_SUBJECT.equals(m.getSubject()));
        assertEquals(1, count("clients"));
        assertEquals(0, count("pending_registrations"));
    }

    @Test
    void aClientWhosePhoneNumberAndSsnAreReusedIsToldBothAndTheApplicantOnlyThatNoAccountWasOpened()
            throws Exception {
        Map<String, Object> owner = validPayload("two.owner@example.com");
        owner.put("phone", "(212) 555-0142");
        submit(owner);
        useLink(awaitToken("two.owner@example.com")).andExpect(status().isNoContent());

        Map<String, Object> reuse = validPayload("two.reuser@example.com");
        reuse.put("phone", "(212) 555-0142");
        submit(reuse);

        assertThat(awaitMail("two.owner@example.com", DETAILS_REUSED_SUBJECT).getText())
                .contains("using the phone number and the Social Security Number on your account");
        assertThat(awaitMail("two.reuser@example.com", APPLICATION_SUBJECT).getText())
                .contains("could not open")
                .doesNotContainIgnoringCase("social security").doesNotContainIgnoringCase("phone");
        assertEquals(1, count("clients"));
    }

    @Test
    void applicantsWithoutAPhoneNumberDoNotCollide() throws Exception {
        Map<String, Object> first = validPayload("no.phone.one@example.com");
        first.put("phone", null);
        Map<String, Object> second = validPayload("no.phone.two@example.com");
        second.put("phone", "");
        second.put("ssn", "987-65-4321");

        register(first);
        register(second);

        assertEquals(2, count("clients"));
    }

    // Applications reserve nothing, so nobody can block an SSN by applying and never confirming.
    @Test
    void twoApplicationsForOneSsnOpenOnlyTheFirstToConfirm() throws Exception {
        submit(validPayload("first.applicant@example.com"));
        Map<String, Object> second = validPayload("second.applicant@example.com");
        second.put("phone", "555-0199");
        submit(second);
        String firstToken = awaitToken("first.applicant@example.com");
        String secondToken = awaitToken("second.applicant@example.com");

        useLink(secondToken).andExpect(status().isNoContent());
        useLink(firstToken).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_VERIFICATION_LINK"));

        assertTrue(clientRepository.existsByEmail("second.applicant@example.com"));
        assertFalse(clientRepository.existsByEmail("first.applicant@example.com"));
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
