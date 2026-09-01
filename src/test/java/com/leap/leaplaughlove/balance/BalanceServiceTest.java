package com.leap.leaplaughlove.balance;

import com.leap.leaplaughlove.trading.Account;
import com.leap.leaplaughlove.trading.AccountRepository;
import com.leap.leaplaughlove.trading.CashLedgerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CashLedgerRepository cashLedgerRepository;

    @Test
    void aggregatesBalancesAcrossAccountsByCurrency() {
        BalanceService balanceService = new BalanceService(accountRepository, cashLedgerRepository);
        UUID clientId = UUID.randomUUID();

        Account usdAccount = newAccount(clientId, "ACC-1", "USD");
        Account eurAccount = newAccount(clientId, "ACC-2", "EUR");

        when(accountRepository.findByClientIdAndStatus(clientId, "ACTIVE"))
                .thenReturn(List.of(usdAccount, eurAccount));
        when(cashLedgerRepository.sumAmountsByAccountIds(List.of(usdAccount.getAccountId(), eurAccount.getAccountId())))
                .thenReturn(List.of(
                        accountTotal(usdAccount.getAccountId(), new BigDecimal("150.00")),
                        accountTotal(eurAccount.getAccountId(), new BigDecimal("75.50"))));

        BalanceResponse response = balanceService.getBalanceForClient(clientId);

        assertEquals(2, response.accounts().size());
        assertEquals(new BigDecimal("150.00"), response.totalsByCurrency().get("USD"));
        assertEquals(new BigDecimal("75.50"), response.totalsByCurrency().get("EUR"));
    }

    @Test
    void returnsZeroBalanceForAccountWithNoLedgerEntries() {
        BalanceService balanceService = new BalanceService(accountRepository, cashLedgerRepository);
        UUID clientId = UUID.randomUUID();
        Account account = newAccount(clientId, "ACC-1", "USD");

        when(accountRepository.findByClientIdAndStatus(clientId, "ACTIVE")).thenReturn(List.of(account));
        when(cashLedgerRepository.sumAmountsByAccountIds(List.of(account.getAccountId()))).thenReturn(List.of());

        BalanceResponse response = balanceService.getBalanceForClient(clientId);

        assertEquals(BigDecimal.ZERO, response.accounts().get(0).balance());
    }

    @Test
    void returnsEmptyBalancesWhenClientHasNoActiveAccounts() {
        BalanceService balanceService = new BalanceService(accountRepository, cashLedgerRepository);
        UUID clientId = UUID.randomUUID();
        when(accountRepository.findByClientIdAndStatus(clientId, "ACTIVE")).thenReturn(List.of());

        BalanceResponse response = balanceService.getBalanceForClient(clientId);

        assertEquals(0, response.accounts().size());
        assertEquals(0, response.totalsByCurrency().size());
    }

    private static Account newAccount(UUID clientId, String accountNumber, String currency) {
        return new Account(UUID.randomUUID(), clientId, accountNumber, "ACTIVE", currency, true, OffsetDateTime.now());
    }

    private static CashLedgerRepository.AccountTotal accountTotal(UUID accountId, BigDecimal total) {
        return new CashLedgerRepository.AccountTotal() {
            @Override
            public UUID getAccountId() {
                return accountId;
            }

            @Override
            public BigDecimal getTotal() {
                return total;
            }
        };
    }
}
