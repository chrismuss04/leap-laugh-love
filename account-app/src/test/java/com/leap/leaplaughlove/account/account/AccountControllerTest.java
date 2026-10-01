package com.leap.leaplaughlove.account.account;

import com.leap.leaplaughlove.account.common.AccountGlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountController Unit Tests")
class AccountControllerTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountAuthorizationService accountAuthorizationService;

    @Mock
    private AccountService accountService;

    private MockMvc mockMvc;

    private final UUID authenticatedClientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AccountController controller = new AccountController(accountRepository, accountAuthorizationService, accountService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AccountGlobalExceptionHandler())
                .build();

        var auth = new UsernamePasswordAuthenticationToken(
                authenticatedClientId, null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /api/account/accounts returns active accounts for authenticated caller")
    void testGetAccountsForClient() throws Exception {
        UUID accountId1 = UUID.randomUUID();
        UUID accountId2 = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        Account account1 = new Account(accountId1, authenticatedClientId, "ACC-001", "ACTIVE", "USD", true, now);
        Account account2 = new Account(accountId2, authenticatedClientId, "ACC-002", "ACTIVE", "USD", false, now);
        account2.setInactiveSince(now.minusDays(40));

        when(accountRepository.findByClientIdAndStatus(authenticatedClientId, AccountAuthorizationService.ACTIVE_STATUS))
                .thenReturn(List.of(account1, account2));

        mockMvc.perform(get("/api/account/accounts")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].accountId", is(accountId1.toString())))
                .andExpect(jsonPath("$[0].accountNumber", is("ACC-001")))
                .andExpect(jsonPath("$[0].status", is("ACTIVE")))
                .andExpect(jsonPath("$[0].tradingEnabled", is(true)))
                .andExpect(jsonPath("$[1].accountId", is(accountId2.toString())))
                .andExpect(jsonPath("$[1].accountNumber", is("ACC-002")))
                .andExpect(jsonPath("$[0].inactiveSince").value(nullValue()))
                .andExpect(jsonPath("$[1].tradingEnabled", is(false)))
                .andExpect(jsonPath("$[1].inactiveSince").isNotEmpty());

        verify(accountRepository).findByClientIdAndStatus(authenticatedClientId, AccountAuthorizationService.ACTIVE_STATUS);
    }

    @Test
    @DisplayName("GET /api/account/accounts returns empty list when caller has no active accounts")
    void testGetAccountsForClientEmpty() throws Exception {
        when(accountRepository.findByClientIdAndStatus(authenticatedClientId, AccountAuthorizationService.ACTIVE_STATUS))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/account/accounts")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/account/accounts/{accountId} returns account summary when authorized")
    void testGetAccountAuthorized() throws Exception {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        Account account = new Account(accountId, authenticatedClientId, "ACC-100", "ACTIVE", "USD", true, now);

        when(accountAuthorizationService.getAuthorizedAccount(accountId)).thenReturn(account);

        mockMvc.perform(get("/api/account/accounts/{accountId}", accountId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is(accountId.toString())))
                .andExpect(jsonPath("$.accountNumber", is("ACC-100")))
                .andExpect(jsonPath("$.baseCurrency", is("USD")))
                .andExpect(jsonPath("$.tradingEnabled", is(true)));

        verify(accountAuthorizationService).getAuthorizedAccount(accountId);
    }

    @Test
    @DisplayName("POST /api/account/accounts returns 201 with the new account's summary")
    void testCreateAccount() throws Exception {
        UUID accountId = UUID.randomUUID();
        Account created = new Account(accountId, authenticatedClientId, "ACC-AB12CD34", "ACTIVE", "USD", true,
                OffsetDateTime.now());
        when(accountService.createAccount(new CreateAccountRequest("USD"))).thenReturn(created);

        mockMvc.perform(post("/api/account/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseCurrency\":\"USD\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId", is(accountId.toString())))
                .andExpect(jsonPath("$.accountNumber", is("ACC-AB12CD34")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.baseCurrency", is("USD")))
                .andExpect(jsonPath("$.tradingEnabled", is(true)));
    }

    @Test
    @DisplayName("POST /api/account/accounts accepts an empty body and delegates with a null request")
    void testCreateAccountWithoutBody() throws Exception {
        Account created = new Account(UUID.randomUUID(), authenticatedClientId, "ACC-AB12CD34", "ACTIVE", "USD",
                true, OffsetDateTime.now());
        when(accountService.createAccount(null)).thenReturn(created);

        mockMvc.perform(post("/api/account/accounts").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseCurrency", is("USD")));

        verify(accountService).createAccount(null);
    }

    @Test
    @DisplayName("POST /api/account/accounts surfaces a service rejection as a 4xx with the message")
    void testCreateAccountRejected() throws Exception {
        when(accountService.createAccount(new CreateAccountRequest("EUR")))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only USD accounts are supported"));

        mockMvc.perform(post("/api/account/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseCurrency\":\"EUR\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Only USD accounts are supported")));
    }

    @Test
    @DisplayName("GET /api/account/accounts/{accountId} returns 403 when access is denied")
    void testGetAccountForbidden() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(accountAuthorizationService.getAuthorizedAccount(accountId))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to account"));

        mockMvc.perform(get("/api/account/accounts/{accountId}", accountId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("403 FORBIDDEN")))
                .andExpect(jsonPath("$.message", is("Access denied to account")));
    }

    @Test
    @DisplayName("GET /api/account/accounts/{accountId} returns 404 when account does not exist")
    void testGetAccountNotFound() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(accountAuthorizationService.getAuthorizedAccount(accountId))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        mockMvc.perform(get("/api/account/accounts/{accountId}", accountId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("404 NOT_FOUND")))
                .andExpect(jsonPath("$.message", is("Account not found")));
    }
}

