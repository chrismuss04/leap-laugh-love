package com.leap.leaplaughlove.service;

import com.leap.leaplaughlove.entity.Account;
import com.leap.leaplaughlove.entity.Client;
import com.leap.leaplaughlove.repository.AccountRepository;
import com.leap.leaplaughlove.repository.ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ClientRepository clientRepository;

    @InjectMocks
    private AccountService accountService;

    private UUID clientId;
    private UUID accountId;
    private Client testClient;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        clientId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        // Create test client
        testClient = new Client();
        testClient.setClientId(clientId);
        testClient.setEmail("client@test.com");
        testClient.setStatus("ACTIVE");
        testClient.setCreatedAt(OffsetDateTime.now());

        // Create test account
        testAccount = new Account();
        testAccount.setAccountId(accountId);
        testAccount.setClient(testClient);
        testAccount.setAccountNumber("ACC-001");
        testAccount.setStatus("ACTIVE");
        testAccount.setBaseCurrency("USD");
        testAccount.setTradingEnabled(true);
        testAccount.setCreatedAt(OffsetDateTime.now());
    }

    // Tests for 1:N relationship
    @Test
    void testCreateAccount_WithValidClient_Success() {
        // Arrange
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(testClient));
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);

        // Act
        Account created = accountService.createAccount(clientId, testAccount);

        // Assert
        assertNotNull(created);
        assertEquals(clientId, created.getClientId());
        assertEquals("ACC-001", created.getAccountNumber());
        assertEquals("ACTIVE", created.getStatus());
        verify(clientRepository, times(1)).findById(clientId);
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void testCreateAccount_WithInvalidClient_ThrowsException() {
        // Arrange
        when(clientRepository.findById(clientId)).thenReturn(Optional.empty());

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            accountService.createAccount(clientId, testAccount);
        });
        assertEquals("Client not found with id: " + clientId, exception.getMessage());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void testGetAccountsByClientId_ReturnsMultipleAccounts() {
        // Arrange - Create multiple accounts for same client (1:N relationship)
        Account account2 = new Account();
        account2.setAccountId(UUID.randomUUID());
        account2.setClient(testClient);
        account2.setAccountNumber("ACC-002");
        account2.setStatus("ACTIVE");

        List<Account> accounts = Arrays.asList(testAccount, account2);

        when(clientRepository.findById(clientId)).thenReturn(Optional.of(testClient));
        when(accountRepository.findByClient(testClient)).thenReturn(accounts);

        // Act
        List<Account> result = accountService.getAccountsByClientId(clientId);

        // Assert
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(acc -> acc.getClient().getClientId().equals(clientId)));
        verify(accountRepository, times(1)).findByClient(testClient);
    }

    @Test
    void testGetAccountsByClientId_WithInvalidClient_ThrowsException() {
        // Arrange
        when(clientRepository.findById(clientId)).thenReturn(Optional.empty());

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            accountService.getAccountsByClientId(clientId);
        });
        assertEquals("Client not found with id: " + clientId, exception.getMessage());
        verify(accountRepository, never()).findByClient(any());
    }

    @Test
    void testGetAccountsByClientId_ClientHasNoAccounts_ReturnsEmptyList() {
        // Arrange
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(testClient));
        when(accountRepository.findByClient(testClient)).thenReturn(Arrays.asList());

        // Act
        List<Account> result = accountService.getAccountsByClientId(clientId);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void testAccountBelongsToClient_ValidOwnership_ReturnsTrue() {
        // Arrange
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(testAccount));

        // Act
        boolean belongs = accountService.accountBelongsToClient(accountId, clientId);

        // Assert
        assertTrue(belongs);
    }

    @Test
    void testAccountBelongsToClient_InvalidOwnership_ReturnsFalse() {
        // Arrange
        UUID differentClientId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(testAccount));

        // Act
        boolean belongs = accountService.accountBelongsToClient(accountId, differentClientId);

        // Assert
        assertFalse(belongs);
    }

    @Test
    void testAccountBelongsToClient_AccountNotFound_ReturnsFalse() {
        // Arrange
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        // Act
        boolean belongs = accountService.accountBelongsToClient(accountId, clientId);

        // Assert
        assertFalse(belongs);
    }

    @Test
    void testGetAccountById_Success() {
        // Arrange
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(testAccount));

        // Act
        Optional<Account> result = accountService.getAccountById(accountId);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(accountId, result.get().getAccountId());
    }

    @Test
    void testGetAccountByAccountNumber_Success() {
        // Arrange
        when(accountRepository.findByAccountNumber("ACC-001")).thenReturn(Optional.of(testAccount));

        // Act
        Optional<Account> result = accountService.getAccountByAccountNumber("ACC-001");

        // Assert
        assertTrue(result.isPresent());
        assertEquals("ACC-001", result.get().getAccountNumber());
    }

    @Test
    void testUpdateAccount_Success() {
        // Arrange
        Account updatedAccount = new Account();
        updatedAccount.setStatus("BLOCKED");
        updatedAccount.setTradingEnabled(false);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(testAccount));
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);

        // Act
        Account result = accountService.updateAccount(accountId, updatedAccount);

        // Assert
        assertNotNull(result);
        verify(accountRepository, times(1)).findById(accountId);
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void testUpdateAccount_NotFound_ThrowsException() {
        // Arrange
        Account updatedAccount = new Account();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            accountService.updateAccount(accountId, updatedAccount);
        });
        assertEquals("Account not found with id: " + accountId, exception.getMessage());
    }

    @Test
    void testDeleteAccount_Success() {
        // Arrange
        doNothing().when(accountRepository).deleteById(accountId);

        // Act
        accountService.deleteAccount(accountId);

        // Assert
        verify(accountRepository, times(1)).deleteById(accountId);
    }

    @Test
    void testGetAllAccounts_Success() {
        // Arrange
        List<Account> accounts = Arrays.asList(testAccount);
        when(accountRepository.findAll()).thenReturn(accounts);

        // Act
        List<Account> result = accountService.getAllAccounts();

        // Assert
        assertEquals(1, result.size());
        verify(accountRepository, times(1)).findAll();
    }
}
