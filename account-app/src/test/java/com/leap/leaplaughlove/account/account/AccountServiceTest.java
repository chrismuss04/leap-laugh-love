package com.leap.leaplaughlove.account.account;

import com.leap.leaplaughlove.account.client.ClientStatus;
import com.leap.leaplaughlove.account.client.ClientStatusRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService Unit Tests")
class AccountServiceTest {

    private static final String ACCOUNT_NUMBER_FORMAT = "^ACC-[A-Z0-9]{8}$";

    @Mock private AccountRepository accountRepository;
    @Mock private ClientStatusRepository clientStatusRepository;

    private AccountService accountService;
    private UUID clientId;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, clientStatusRepository);
        clientId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(clientId, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void givenClientStatus(String status) {
        when(clientStatusRepository.findById(clientId)).thenReturn(Optional.of(new ClientStatus(clientId, status)));
    }

    private void givenSaveReturnsArgument() {
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("createAccount — ACTIVE client gets an ACTIVE, trading-enabled USD account owned by the caller")
    void createAccount_activeClient() {
        givenClientStatus("ACTIVE");
        givenSaveReturnsArgument();

        Account created = accountService.createAccount(new CreateAccountRequest("USD"));

        assertEquals(clientId, created.getClientId());
        assertEquals("ACTIVE", created.getStatus());
        assertEquals("USD", created.getBaseCurrency());
        assertTrue(created.isTradingEnabled());
        assertNotNull(created.getCreatedAt());
    }

    @Test
    @DisplayName("createAccount — PENDING client gets a PENDING account with trading disabled")
    void createAccount_pendingClient() {
        givenClientStatus("PENDING");
        givenSaveReturnsArgument();

        Account created = accountService.createAccount(new CreateAccountRequest("USD"));

        assertEquals("PENDING", created.getStatus());
        assertFalse(created.isTradingEnabled());
    }

    @Test
    @DisplayName("createAccount — LOCKED client gets a BLOCKED account with trading disabled")
    void createAccount_lockedClient() {
        givenClientStatus("LOCKED");
        givenSaveReturnsArgument();

        Account created = accountService.createAccount(new CreateAccountRequest("USD"));

        assertEquals("BLOCKED", created.getStatus());
        assertFalse(created.isTradingEnabled());
    }

    @Test
    @DisplayName("createAccount — DELETED client is rejected with 403 and nothing is saved")
    void createAccount_deletedClient() {
        givenClientStatus("DELETED");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> accountService.createAccount(new CreateAccountRequest("USD")));

        assertEquals(403, ex.getStatusCode().value());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("createAccount — unknown client is rejected with 404 and nothing is saved")
    void createAccount_clientNotFound() {
        when(clientStatusRepository.findById(clientId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> accountService.createAccount(new CreateAccountRequest("USD")));

        assertEquals(404, ex.getStatusCode().value());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("createAccount — leaves the id null so Hibernate generates the UUID, and number matches ACC-XXXXXXXX")
    void createAccount_idAndNumberFormat() {
        givenClientStatus("ACTIVE");
        givenSaveReturnsArgument();

        accountService.createAccount(new CreateAccountRequest("USD"));

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertNull(captor.getValue().getAccountId());
        assertTrue(captor.getValue().getAccountNumber().matches(ACCOUNT_NUMBER_FORMAT),
                "unexpected account number: " + captor.getValue().getAccountNumber());
    }

    @Test
    @DisplayName("createAccount — regenerates the account number when it collides with an existing one")
    void createAccount_retriesOnCollision() {
        givenClientStatus("ACTIVE");
        givenSaveReturnsArgument();
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(true, false);

        Account created = accountService.createAccount(new CreateAccountRequest("USD"));

        assertTrue(created.getAccountNumber().matches(ACCOUNT_NUMBER_FORMAT));
        verify(accountRepository, times(2)).existsByAccountNumber(anyString());
    }

    @Test
    @DisplayName("createAccount — gives up with 500 after repeated account number collisions")
    void createAccount_givesUpAfterRepeatedCollisions() {
        givenClientStatus("ACTIVE");
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> accountService.createAccount(new CreateAccountRequest("USD")));

        assertEquals(500, ex.getStatusCode().value());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("createAccount — defaults to USD when the request or currency is absent")
    void createAccount_defaultsToUsd() {
        givenClientStatus("ACTIVE");
        givenSaveReturnsArgument();

        assertEquals("USD", accountService.createAccount(null).getBaseCurrency());
        assertEquals("USD", accountService.createAccount(new CreateAccountRequest(null)).getBaseCurrency());
        assertEquals("USD", accountService.createAccount(new CreateAccountRequest("  ")).getBaseCurrency());
    }

    @Test
    @DisplayName("createAccount — rejects a non-USD currency with 400")
    void createAccount_rejectsNonUsd() {
        givenClientStatus("ACTIVE");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> accountService.createAccount(new CreateAccountRequest("EUR")));

        assertEquals(400, ex.getStatusCode().value());
        assertEquals("Only USD accounts are supported", ex.getReason());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("createAccount — a client can open several accounts, each with its own number")
    void createAccount_multipleAccountsForOneClient() {
        givenClientStatus("ACTIVE");
        givenSaveReturnsArgument();

        Account first = accountService.createAccount(new CreateAccountRequest("USD"));
        Account second = accountService.createAccount(new CreateAccountRequest("USD"));

        assertEquals(first.getClientId(), second.getClientId());
        assertNotEquals(first.getAccountNumber(), second.getAccountNumber());
        verify(accountRepository, times(2)).save(any(Account.class));
    }
}
