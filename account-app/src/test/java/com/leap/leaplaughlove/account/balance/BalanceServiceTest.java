package com.leap.leaplaughlove.account.balance;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountRepository;
import com.leap.leaplaughlove.account.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.account.ledger.CashLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BalanceService Unit Tests")
class BalanceServiceTest {

    // Increase Test Coverage: isolate the authenticated client between tests.
    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // Verify null, zero, and negative cash amounts are rejected before accessing storage.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "-0.01"})
    void rejectsInvalidAmounts(String value) {
        var request = new CashMovementRequest(value == null ? null : new BigDecimal(value), "invalid");
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> balanceService.deposit(accountId, request)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> balanceService.withdraw(accountId, request)).getStatusCode().value());
        verifyNoInteractions(accountRepository, cashLedgerRepository);
    }

    // Verify withdrawing the full balance succeeds and leaves zero cash.
    @Test
    void withdrawsExactBalance() {
        when(accountRepository.findByAccountIdAndClientIdForUpdate(accountId, clientId)).thenReturn(Optional.of(account));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD")).thenReturn(new BigDecimal("100.00"));
        when(cashLedgerRepository.save(any(CashLedgerEntry.class))).thenAnswer(call -> call.getArgument(0));

        var response = balanceService.withdraw(accountId, new CashMovementRequest(new BigDecimal("100.00"), "all cash"));

        assertEquals(0, response.balanceAfter().compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("-100.00"), response.amount());
    }

    // Verify deposits round half up to cents in both the ledger and resulting balance.
    @Test
    void roundsDepositToCents() {
        when(accountRepository.findByAccountIdAndClientIdForUpdate(accountId, clientId)).thenReturn(Optional.of(account));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD")).thenReturn(new BigDecimal("100.00"));
        when(cashLedgerRepository.save(any(CashLedgerEntry.class))).thenAnswer(call -> call.getArgument(0));

        var response = balanceService.deposit(accountId, new CashMovementRequest(new BigDecimal("10.005"), "rounded"));

        assertEquals(new BigDecimal("10.01"), response.amount());
        assertEquals(new BigDecimal("110.01"), response.balanceAfter());
        var saved = ArgumentCaptor.forClass(CashLedgerEntry.class);
        verify(cashLedgerRepository).save(saved.capture());
        assertEquals(response.amount(), saved.getValue().getAmount());
    }

    // Verify clients without active accounts receive empty balances without querying the ledger.
    @Test
    void returnsEmptyBalances() {
        when(accountRepository.findByClientIdAndStatus(clientId, "ACTIVE")).thenReturn(List.of());
        var response = balanceService.getBalanceForClient();
        assertTrue(response.accounts().isEmpty());
        assertTrue(response.totalByCurrency().isEmpty());
        verifyNoInteractions(cashLedgerRepository);
    }

    @Mock private AccountRepository accountRepository;
    @Mock private CashLedgerRepository cashLedgerRepository;

    @InjectMocks private BalanceService balanceService;

    private UUID clientId;
    private UUID accountId;
    private Account account;

    @BeforeEach
    void setUp() {
        clientId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        account = new Account(accountId, clientId, "ACC-12345", "ACTIVE", "USD", true, OffsetDateTime.now());

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(clientId, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("deposit — saves entry and returns correct balanceAfter")
    void testDeposit_Success() {
        var request = new CashMovementRequest(new BigDecimal("100.00"), "Deposit test");

        // LLL-133
        when(accountRepository.findByAccountIdAndClientIdForUpdate(accountId, clientId)).thenReturn(Optional.of(account));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD"))
                .thenReturn(new BigDecimal("250.00"));

        when(cashLedgerRepository.save(any(CashLedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CashTransactionResponse response = balanceService.deposit(accountId, request);

        // LLL-133
        var cashMovementOrder = inOrder(accountRepository, cashLedgerRepository);
        cashMovementOrder.verify(accountRepository).findByAccountIdAndClientIdForUpdate(accountId, clientId);
        cashMovementOrder.verify(cashLedgerRepository).sumAmountByAccountIdAndCurrency(accountId, "USD");
        cashMovementOrder.verify(cashLedgerRepository).save(any(CashLedgerEntry.class));

        assertNotNull(response);
        assertEquals("DEPOSIT", response.entryType());
        assertEquals(new BigDecimal("100.00"), response.amount());
        assertEquals("USD", response.currency());
        assertEquals(new BigDecimal("350.00"), response.balanceAfter());

        ArgumentCaptor<CashLedgerEntry> captor = ArgumentCaptor.forClass(CashLedgerEntry.class);
        verify(cashLedgerRepository).save(captor.capture());
        assertEquals(new BigDecimal("100.00"), captor.getValue().getAmount());
    }

    @Test
    @DisplayName("withdraw — saves negative amount entry and returns correct balanceAfter")
    void testWithdraw_Success() {
        var request = new CashMovementRequest(new BigDecimal("50.00"), "Withdrawal test");

        // LLL-133
        when(accountRepository.findByAccountIdAndClientIdForUpdate(accountId, clientId)).thenReturn(Optional.of(account));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD"))
                .thenReturn(new BigDecimal("200.00"));

        when(cashLedgerRepository.save(any(CashLedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CashTransactionResponse response = balanceService.withdraw(accountId, request);

        // LLL-133
        var cashMovementOrder = inOrder(accountRepository, cashLedgerRepository);
        cashMovementOrder.verify(accountRepository).findByAccountIdAndClientIdForUpdate(accountId, clientId);
        cashMovementOrder.verify(cashLedgerRepository).sumAmountByAccountIdAndCurrency(accountId, "USD");
        cashMovementOrder.verify(cashLedgerRepository).save(any(CashLedgerEntry.class));

        assertNotNull(response);
        assertEquals("WITHDRAWAL", response.entryType());
        assertEquals(new BigDecimal("-50.00"), response.amount());
        assertEquals(new BigDecimal("150.00"), response.balanceAfter());

        ArgumentCaptor<CashLedgerEntry> captor = ArgumentCaptor.forClass(CashLedgerEntry.class);
        verify(cashLedgerRepository).save(captor.capture());
        assertEquals(new BigDecimal("-50.00"), captor.getValue().getAmount());
    }

    @Test
    @DisplayName("withdraw — throws ResponseStatusException when withdrawal exceeds available balance")
    void testWithdraw_ExceedsBalance() {
        var request = new CashMovementRequest(new BigDecimal("300.00"), "Overdraft");

        // LLL-133
        when(accountRepository.findByAccountIdAndClientIdForUpdate(accountId, clientId)).thenReturn(Optional.of(account));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD"))
                .thenReturn(new BigDecimal("100.00"));

        // LLL-133
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> balanceService.withdraw(accountId, request));
        assertEquals(400, ex.getStatusCode().value());
        assertEquals("Withdrawal amount cannot exceed available account balance", ex.getReason());
        var cashMovementOrder = inOrder(accountRepository, cashLedgerRepository);
        cashMovementOrder.verify(accountRepository).findByAccountIdAndClientIdForUpdate(accountId, clientId);
        cashMovementOrder.verify(cashLedgerRepository).sumAmountByAccountIdAndCurrency(accountId, "USD");
        verify(cashLedgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("deposit — throws Not Found when client does not own account")
    void testDeposit_NotFoundForUnownedAccount() {
        // LLL-133
        when(accountRepository.findByAccountIdAndClientIdForUpdate(accountId, clientId)).thenReturn(Optional.empty());

        var request = new CashMovementRequest(new BigDecimal("50.00"), "Unauthorized");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> balanceService.deposit(accountId, request));
        assertEquals(404, ex.getStatusCode().value());
        // LLL-133
        verifyNoInteractions(cashLedgerRepository);
    }
}

