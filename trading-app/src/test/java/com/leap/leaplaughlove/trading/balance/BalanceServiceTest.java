package com.leap.leaplaughlove.trading.balance;

import com.leap.leaplaughlove.trading.account.Account;
import com.leap.leaplaughlove.trading.account.AccountRepository;
import com.leap.leaplaughlove.trading.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.trading.ledger.CashLedgerRepository;
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

        when(accountRepository.findByAccountIdAndClientId(accountId, clientId)).thenReturn(Optional.of(account));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD"))
                .thenReturn(new BigDecimal("250.00"));

        when(cashLedgerRepository.save(any(CashLedgerEntry.class))).thenAnswer(invocation -> {
            CashLedgerEntry entry = invocation.getArgument(0);
            return entry;
        });

        CashTransactionResponse response = balanceService.deposit(accountId, request);

        assertNotNull(response);
        assertEquals("DEPOSIT", response.entryType());
        assertEquals(new BigDecimal("100.00"), response.amount());
        assertEquals("USD", response.currency());
        assertEquals(new BigDecimal("350.00"), response.balanceAfter());

        ArgumentCaptor<com.leap.leaplaughlove.trading.ledger.CashLedgerEntry> captor =
                ArgumentCaptor.forClass(com.leap.leaplaughlove.trading.ledger.CashLedgerEntry.class);
        verify(cashLedgerRepository).save(captor.capture());
        assertEquals(new BigDecimal("100.00"), captor.getValue().getAmount());
    }

    @Test
    @DisplayName("withdraw — saves negative amount entry and returns correct balanceAfter")
    void testWithdraw_Success() {
        var request = new CashMovementRequest(new BigDecimal("50.00"), "Withdrawal test");

        when(accountRepository.findByAccountIdAndClientId(accountId, clientId)).thenReturn(Optional.of(account));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD"))
                .thenReturn(new BigDecimal("200.00"));

        when(cashLedgerRepository.save(any(CashLedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CashTransactionResponse response = balanceService.withdraw(accountId, request);

        assertNotNull(response);
        assertEquals("WITHDRAWAL", response.entryType());
        assertEquals(new BigDecimal("-50.00"), response.amount());
        assertEquals(new BigDecimal("150.00"), response.balanceAfter());

        ArgumentCaptor<com.leap.leaplaughlove.trading.ledger.CashLedgerEntry> captor =
                ArgumentCaptor.forClass(com.leap.leaplaughlove.trading.ledger.CashLedgerEntry.class);
        verify(cashLedgerRepository).save(captor.capture());
        assertEquals(new BigDecimal("-50.00"), captor.getValue().getAmount());
    }

    @Test
    @DisplayName("withdraw — throws ResponseStatusException when withdrawal exceeds available balance")
    void testWithdraw_ExceedsBalance() {
        var request = new CashMovementRequest(new BigDecimal("300.00"), "Overdraft");

        when(accountRepository.findByAccountIdAndClientId(accountId, clientId)).thenReturn(Optional.of(account));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD"))
                .thenReturn(new BigDecimal("100.00"));

        assertThrows(ResponseStatusException.class, () -> balanceService.withdraw(accountId, request));
        verify(cashLedgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("deposit — throws Not Found when client does not own account")
    void testDeposit_NotFoundForUnownedAccount() {
        when(accountRepository.findByAccountIdAndClientId(accountId, clientId)).thenReturn(Optional.empty());

        var request = new CashMovementRequest(new BigDecimal("50.00"), "Unauthorized");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> balanceService.deposit(accountId, request));
        assertEquals(404, ex.getStatusCode().value());
    }
}
