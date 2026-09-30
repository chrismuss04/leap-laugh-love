package com.leap.leaplaughlove.iam.account;

import com.leap.leaplaughlove.common.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Mock private JwtService jwtService;

    @InjectMocks private AccountProvisioningListener listener;

    @Test
    @DisplayName("opens a USD account as the new client, then deposits into the account it returned")
    void createsAccountThenDeposits() {
        when(jwtService.generateToken(clientId, "new.client@example.com")).thenReturn("client-token");
        when(accountClient.createAccount("client-token", "USD")).thenReturn(accountId);

        listener.onClientRegistered(event);

        var order = inOrder(accountClient);
        order.verify(accountClient).createAccount("client-token", "USD");
        order.verify(accountClient).deposit("client-token", accountId, deposit, "Initial deposit");
    }

    @Test
    @DisplayName("makes no deposit when the account could not be created, and does not throw")
    void noDepositWhenAccountCreationFails() {
        when(jwtService.generateToken(clientId, "new.client@example.com")).thenReturn("client-token");
        when(accountClient.createAccount(anyString(), anyString()))
                .thenThrow(new ResourceAccessException("account-app unreachable"));

        assertDoesNotThrow(() -> listener.onClientRegistered(event));

        verify(accountClient, never()).deposit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("swallows a failed deposit and does not retry it")
    void failedDepositIsNotRetried() {
        when(jwtService.generateToken(clientId, "new.client@example.com")).thenReturn("client-token");
        when(accountClient.createAccount("client-token", "USD")).thenReturn(accountId);
        org.mockito.Mockito.doThrow(new RestClientException("read timed out"))
                .when(accountClient).deposit(any(), any(), any(), any());

        assertDoesNotThrow(() -> listener.onClientRegistered(event));

        verify(accountClient, times(1)).createAccount("client-token", "USD");
        verify(accountClient, times(1)).deposit("client-token", accountId, deposit, "Initial deposit");
    }

    @Test
    @DisplayName("makes no account calls when the token cannot be issued, and does not throw")
    void noCallsWhenTokenCannotBeIssued() {
        when(jwtService.generateToken(clientId, "new.client@example.com"))
                .thenThrow(new IllegalStateException("signing failed"));

        assertDoesNotThrow(() -> listener.onClientRegistered(event));

        verifyNoInteractions(accountClient);
    }
}
