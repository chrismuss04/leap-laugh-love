package com.leap.leaplaughlove.trading.account;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
// LLL-133
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountAuthorizationService Tests")
class AccountAuthorizationServiceTest {

    @Mock private AccountRepository accountRepository;

    private AccountAuthorizationService authorizationService;

    private UUID clientId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        authorizationService = new AccountAuthorizationService(accountRepository);
        clientId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        var auth = new UsernamePasswordAuthenticationToken(clientId, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // LLL-133
    @Test
    @DisplayName("Locking cash authorization allows an active account even when trading is disabled")
    void testGetAuthorizedAccountForUpdate_Success() {
        Account account = new Account(accountId, clientId, "ACC-01", "ACTIVE", "USD", false, OffsetDateTime.now());
        when(accountRepository.findByAccountIdAndClientIdForUpdate(accountId, clientId))
                .thenReturn(Optional.of(account));

        assertSame(account, authorizationService.getAuthorizedAccountForUpdate(accountId));

        verify(accountRepository).findByAccountIdAndClientIdForUpdate(accountId, clientId);
        verifyNoMoreInteractions(accountRepository);
    }

    // LLL-133
    @Test
    @DisplayName("Both locking authorization methods reject missing or unowned accounts")
    void testLockingAuthorization_NotFound() {
        when(accountRepository.findByAccountIdAndClientIdForUpdate(accountId, clientId))
                .thenReturn(Optional.empty());

        ResponseStatusException cashException = assertThrows(ResponseStatusException.class,
                () -> authorizationService.getAuthorizedAccountForUpdate(accountId));
        ResponseStatusException tradeException = assertThrows(ResponseStatusException.class,
                () -> authorizationService.getAuthorizedTradingAccountForUpdate(accountId));

        assertEquals(HttpStatus.NOT_FOUND, cashException.getStatusCode());
        assertEquals("Account not found", cashException.getReason());
        assertEquals(HttpStatus.NOT_FOUND, tradeException.getStatusCode());
        assertEquals("Account not found", tradeException.getReason());
    }

    // LLL-133
    @Test
    @DisplayName("Both locking authorization methods reject inactive accounts")
    void testLockingAuthorization_Inactive() {
        Account account = new Account(accountId, clientId, "ACC-01", "BLOCKED", "USD", true, OffsetDateTime.now());
        when(accountRepository.findByAccountIdAndClientIdForUpdate(accountId, clientId))
                .thenReturn(Optional.of(account));

        ResponseStatusException cashException = assertThrows(ResponseStatusException.class,
                () -> authorizationService.getAuthorizedAccountForUpdate(accountId));
        ResponseStatusException tradeException = assertThrows(ResponseStatusException.class,
                () -> authorizationService.getAuthorizedTradingAccountForUpdate(accountId));

        assertEquals(HttpStatus.BAD_REQUEST, cashException.getStatusCode());
        assertEquals("Account is not active", cashException.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, tradeException.getStatusCode());
        assertEquals("Account is not active", tradeException.getReason());
    }

    // LLL-133
    @Test
    @DisplayName("Locking trading authorization returns an active account with trading enabled")
    void testGetAuthorizedTradingAccountForUpdate_Success() {
        Account account = new Account(accountId, clientId, "ACC-01", "ACTIVE", "USD", true, OffsetDateTime.now());
        when(accountRepository.findByAccountIdAndClientIdForUpdate(accountId, clientId))
                .thenReturn(Optional.of(account));

        assertSame(account, authorizationService.getAuthorizedTradingAccountForUpdate(accountId));

        verify(accountRepository).findByAccountIdAndClientIdForUpdate(accountId, clientId);
        verifyNoMoreInteractions(accountRepository);
    }

    // LLL-133
    @Test
    @DisplayName("Locking trading authorization rejects accounts with trading disabled")
    void testGetAuthorizedTradingAccountForUpdate_TradingDisabled() {
        Account account = new Account(accountId, clientId, "ACC-01", "ACTIVE", "USD", false, OffsetDateTime.now());
        when(accountRepository.findByAccountIdAndClientIdForUpdate(accountId, clientId))
                .thenReturn(Optional.of(account));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authorizationService.getAuthorizedTradingAccountForUpdate(accountId));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Trading is disabled for this account", ex.getReason());
    }

    @Test
    @DisplayName("getAuthorizedAccount returns Account when owned and ACTIVE")
    void testGetAuthorizedAccount_Success() {
        Account account = new Account(accountId, clientId, "ACC-01", "ACTIVE", "USD", true, OffsetDateTime.now());
        when(accountRepository.findByAccountIdAndClientId(accountId, clientId)).thenReturn(Optional.of(account));

        Account result = authorizationService.getAuthorizedAccount(accountId);
        assertNotNull(result);
        assertEquals(accountId, result.getAccountId());
    }

    @Test
    @DisplayName("getAuthorizedAccount throws 404 when account does not exist or unowned")
    void testGetAuthorizedAccount_NotFound() {
        when(accountRepository.findByAccountIdAndClientId(accountId, clientId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authorizationService.getAuthorizedAccount(accountId));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("getAuthorizedAccount throws 400 when account is not ACTIVE")
    void testGetAuthorizedAccount_Inactive() {
        Account account = new Account(accountId, clientId, "ACC-01", "BLOCKED", "USD", true, OffsetDateTime.now());
        when(accountRepository.findByAccountIdAndClientId(accountId, clientId)).thenReturn(Optional.of(account));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authorizationService.getAuthorizedAccount(accountId));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Account is not active"));
    }

    @Test
    @DisplayName("getAuthorizedTradingAccount returns Account when trading is enabled")
    void testGetAuthorizedTradingAccount_Success() {
        Account account = new Account(accountId, clientId, "ACC-01", "ACTIVE", "USD", true, OffsetDateTime.now());
        when(accountRepository.findByAccountIdAndClientId(accountId, clientId)).thenReturn(Optional.of(account));

        Account result = authorizationService.getAuthorizedTradingAccount(accountId);
        assertNotNull(result);
        assertTrue(result.isTradingEnabled());
    }

    @Test
    @DisplayName("getAuthorizedTradingAccount throws 400 when trading is disabled (locked)")
    void testGetAuthorizedTradingAccount_TradingDisabled() {
        Account account = new Account(accountId, clientId, "ACC-01", "ACTIVE", "USD", false, OffsetDateTime.now());
        when(accountRepository.findByAccountIdAndClientId(accountId, clientId)).thenReturn(Optional.of(account));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authorizationService.getAuthorizedTradingAccount(accountId));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Trading is disabled"));
    }
}

