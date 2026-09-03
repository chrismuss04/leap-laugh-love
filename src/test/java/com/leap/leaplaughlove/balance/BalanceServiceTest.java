package com.leap.leaplaughlove.balance;

import com.leap.leaplaughlove.trading.Account;
import com.leap.leaplaughlove.trading.AccountRepository;
import com.leap.leaplaughlove.trading.CashLedgerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CashLedgerRepository cashLedgerRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsZeroBalanceForAccountWithNoLedgerEntries() {
        BalanceService balanceService = new BalanceService(accountRepository, cashLedgerRepository);
        UUID clientId = UUID.randomUUID();
        authenticate(clientId);
        Account account = newAccount(clientId, "ACC-1", "USD");

        when(accountRepository.findByClientIdAndStatus(clientId, "ACTIVE")).thenReturn(List.of(account));
        when(cashLedgerRepository.sumAmountsByAccountIds(List.of(account.getAccountId()))).thenReturn(List.of());

        BalanceResponse response = balanceService.getBalanceForClient();

        assertEquals(BigDecimal.ZERO, response.accounts().get(0).balance());
    }

    @Test
    void returnsEmptyBalancesWhenClientHasNoActiveAccounts() {
        BalanceService balanceService = new BalanceService(accountRepository, cashLedgerRepository);
        UUID clientId = UUID.randomUUID();
        authenticate(clientId);
        when(accountRepository.findByClientIdAndStatus(clientId, "ACTIVE")).thenReturn(List.of());

        BalanceResponse response = balanceService.getBalanceForClient();

        assertEquals(0, response.accounts().size());
        assertEquals(0, response.totalsByCurrency().size());
    }

        @Test
        void depositCreatesPositiveLedgerEntryAndReturnsUpdatedBalance() {
        BalanceService balanceService = new BalanceService(accountRepository, cashLedgerRepository);
        UUID clientId = UUID.randomUUID();
        authenticate(clientId);
        Account account = newAccount(clientId, "ACC-1", "USD");

        when(accountRepository.findById(account.getAccountId())).thenReturn(java.util.Optional.of(account));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(account.getAccountId(), "USD"))
            .thenReturn(new BigDecimal("100.00"));
        when(cashLedgerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CashTransactionResponse response = balanceService.deposit(
            account.getAccountId(),
            new CashMovementRequest(new BigDecimal("50.00"), "Funding"));

        ArgumentCaptor<com.leap.leaplaughlove.trading.CashLedgerEntry> captor =
            ArgumentCaptor.forClass(com.leap.leaplaughlove.trading.CashLedgerEntry.class);
        verify(cashLedgerRepository).save(captor.capture());

        assertEquals("DEPOSIT", captor.getValue().getEntryType());
        assertEquals(new BigDecimal("50.00"), captor.getValue().getAmount());
        assertEquals(new BigDecimal("150.00"), response.balanceAfter());
        }

        @Test
        void withdrawCreatesNegativeLedgerEntryWhenFundsAreSufficient() {
        BalanceService balanceService = new BalanceService(accountRepository, cashLedgerRepository);
        UUID clientId = UUID.randomUUID();
        authenticate(clientId);
        Account account = newAccount(clientId, "ACC-1", "USD");

        when(accountRepository.findById(account.getAccountId())).thenReturn(java.util.Optional.of(account));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(account.getAccountId(), "USD"))
            .thenReturn(new BigDecimal("80.00"));
        when(cashLedgerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CashTransactionResponse response = balanceService.withdraw(
            account.getAccountId(),
            new CashMovementRequest(new BigDecimal("30.00"), "ATM"));

        ArgumentCaptor<com.leap.leaplaughlove.trading.CashLedgerEntry> captor =
            ArgumentCaptor.forClass(com.leap.leaplaughlove.trading.CashLedgerEntry.class);
        verify(cashLedgerRepository).save(captor.capture());

        assertEquals("WITHDRAWAL", captor.getValue().getEntryType());
        assertEquals(new BigDecimal("-30.00"), captor.getValue().getAmount());
        assertEquals(new BigDecimal("50.00"), response.balanceAfter());
        }

        @Test
        void withdrawRejectsWhenAmountExceedsAvailableBalance() {
        BalanceService balanceService = new BalanceService(accountRepository, cashLedgerRepository);
        UUID clientId = UUID.randomUUID();
        authenticate(clientId);
        Account account = newAccount(clientId, "ACC-1", "USD");

        when(accountRepository.findById(account.getAccountId())).thenReturn(java.util.Optional.of(account));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(account.getAccountId(), "USD"))
            .thenReturn(new BigDecimal("10.00"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
            balanceService.withdraw(
                account.getAccountId(),
                new CashMovementRequest(new BigDecimal("11.00"), "Too much")));

        assertEquals(400, ex.getStatusCode().value());
        verify(cashLedgerRepository, never()).save(any());
        }

        @Test
        void rejectsNonPositiveAmounts() {
        BalanceService balanceService = new BalanceService(accountRepository, cashLedgerRepository);
        UUID clientId = UUID.randomUUID();
        authenticate(clientId);
        Account account = newAccount(clientId, "ACC-1", "USD");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
            balanceService.deposit(
                account.getAccountId(),
                new CashMovementRequest(BigDecimal.ZERO, "Invalid")));

        assertEquals(400, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("greater than zero"));
        verify(cashLedgerRepository, never()).save(any());
        }

        @Test
        void rejectsModificationWhenAuthenticatedUserDoesNotOwnAccount() {
        BalanceService balanceService = new BalanceService(accountRepository, cashLedgerRepository);
        UUID ownerClientId = UUID.randomUUID();
        UUID attackerClientId = UUID.randomUUID();
        Account account = newAccount(ownerClientId, "ACC-1", "USD");

        when(accountRepository.findById(account.getAccountId())).thenReturn(java.util.Optional.of(account));
        authenticate(attackerClientId);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
            balanceService.deposit(
                account.getAccountId(),
                new CashMovementRequest(new BigDecimal("1.00"), "Forbidden")));

        assertEquals(403, ex.getStatusCode().value());
        verify(cashLedgerRepository, never()).save(any());
        }

    @Test
    void rejectsWhenRequestIsNotAuthenticated() {
        BalanceService balanceService = new BalanceService(accountRepository, cashLedgerRepository);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, balanceService::getBalanceForClient);

        assertEquals(401, ex.getStatusCode().value());
    }

    private static void authenticate(UUID clientId) {
        var authentication = new UsernamePasswordAuthenticationToken(clientId, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static Account newAccount(UUID clientId, String accountNumber, String currency) {
        return new Account(UUID.randomUUID(), clientId, accountNumber, "ACTIVE", currency, true, OffsetDateTime.now());
    }

}
