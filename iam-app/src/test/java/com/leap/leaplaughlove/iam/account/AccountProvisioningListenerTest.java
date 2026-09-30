package com.leap.leaplaughlove.iam.account;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.iam.session.ClientSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountProvisioningListener Unit Tests")
class AccountProvisioningListenerTest {

    private final UUID clientId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final BigDecimal deposit = new BigDecimal("7500.00");
    private final ClientRegisteredEvent event = new ClientRegisteredEvent(clientId, "new.client@example.com", deposit);

    @Mock private AccountClient accountClient;
    @Mock private ClientSessionRepository sessionRepository;
    @Mock private PlatformTransactionManager transactionManager;

    private JwtService jwtService;
    private AccountProvisioningListener listener;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService("unit-test-secret-unit-test-secret-unit-test-secret", 30);
        listener = new AccountProvisioningListener(accountClient, jwtService, sessionRepository, transactionManager);
    }

    private String tokenSentToAccountApp() {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(accountClient).createAccount(token.capture(), eq("USD"));
        return token.getValue();
    }

    @Test
    @DisplayName("opens a USD account as the new client, then deposits into the account it returned")
    void createsAccountThenDeposits() {
        when(accountClient.createAccount(anyString(), eq("USD"))).thenReturn(accountId);

        listener.onClientRegistered(event);

        String token = tokenSentToAccountApp();
        assertEquals(clientId, jwtService.parseAndValidate(token));
        var order = inOrder(accountClient);
        order.verify(accountClient).createAccount(token, "USD");
        order.verify(accountClient).deposit(token, accountId, deposit, "Initial deposit");
    }

    @Test
    @DisplayName("authenticates with a token backed by a stored session, which is revoked once the calls are done")
    void usesThenRevokesASessionBackedToken() {
        when(accountClient.createAccount(anyString(), eq("USD"))).thenReturn(accountId);

        listener.onClientRegistered(event);

        JwtService.TokenIdentity identity = jwtService.parseIdentity(tokenSentToAccountApp());
        assertNotNull(identity.sessionId(), "account-app rejects tokens without a session");

        ArgumentCaptor<Instant> issuedAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);
        var order = inOrder(sessionRepository, accountClient);
        order.verify(sessionRepository).create(eq(identity.sessionId()), eq(clientId),
                issuedAt.capture(), expiresAt.capture());
        order.verify(accountClient).createAccount(anyString(), anyString());
        order.verify(accountClient).deposit(anyString(), any(), any(), anyString());
        order.verify(sessionRepository).revoke(eq(identity.sessionId()), eq(clientId), any(Instant.class));
        assertEquals(expiresAt.getValue(), identity.expiresAt(), "the token must expire with its stored session");
        assertTrue(expiresAt.getValue().isAfter(issuedAt.getValue()));
    }

    @Test
    @DisplayName("writes the session in its own transaction, since the registration's has already ended")
    void sessionWritesUseTheirOwnTransaction() {
        when(accountClient.createAccount(anyString(), eq("USD"))).thenReturn(accountId);

        listener.onClientRegistered(event);

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(definition.capture());
        definition.getAllValues().forEach(d ->
                assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, d.getPropagationBehavior()));
    }

    @Test
    @DisplayName("makes no deposit when the account could not be created, revokes the session, and does not throw")
    void noDepositWhenAccountCreationFails() {
        when(accountClient.createAccount(anyString(), anyString()))
                .thenThrow(new ResourceAccessException("account-app unreachable"));

        assertDoesNotThrow(() -> listener.onClientRegistered(event));

        verify(accountClient, never()).deposit(any(), any(), any(), any());
        verify(sessionRepository).revoke(any(UUID.class), eq(clientId), any(Instant.class));
    }

    @Test
    @DisplayName("swallows a failed deposit and does not retry it")
    void failedDepositIsNotRetried() {
        when(accountClient.createAccount(anyString(), eq("USD"))).thenReturn(accountId);
        doThrow(new RestClientException("read timed out"))
                .when(accountClient).deposit(any(), any(), any(), any());

        assertDoesNotThrow(() -> listener.onClientRegistered(event));

        String token = tokenSentToAccountApp();
        verify(accountClient, times(1)).deposit(token, accountId, deposit, "Initial deposit");
        verify(sessionRepository).revoke(any(UUID.class), eq(clientId), any(Instant.class));
    }

    @Test
    @DisplayName("makes no account calls when the session cannot be stored, and does not throw")
    void noCallsWhenSessionCannotBeStored() {
        doThrow(new IllegalStateException("database down"))
                .when(sessionRepository).create(any(), any(), any(), any());

        assertDoesNotThrow(() -> listener.onClientRegistered(event));

        verifyNoInteractions(accountClient);
        verify(sessionRepository, never()).revoke(any(), any(), any());
    }

    @Test
    @DisplayName("still succeeds when the session cannot be revoked afterwards")
    void revokeFailureIsSwallowed() {
        when(accountClient.createAccount(anyString(), eq("USD"))).thenReturn(accountId);
        doThrow(new IllegalStateException("database down"))
                .when(sessionRepository).revoke(any(), any(), any());

        assertDoesNotThrow(() -> listener.onClientRegistered(event));

        verify(accountClient).deposit(anyString(), eq(accountId), eq(deposit), eq("Initial deposit"));
    }
}
