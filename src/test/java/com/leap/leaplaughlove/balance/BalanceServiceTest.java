package com.leap.leaplaughlove.balance;

import com.leap.leaplaughlove.trading.Account;
import com.leap.leaplaughlove.trading.AccountRepository;
import com.leap.leaplaughlove.trading.CashLedgerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
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
    void aggregatesBalancesAcrossAccountsByCurrency() throws Exception {
        BalanceService balanceService = new BalanceService(accountRepository, cashLedgerRepository);
        UUID clientId = UUID.randomUUID();

        Account usdAccount = newAccount(UUID.randomUUID(), clientId, "ACC-1", "USD");
        Account eurAccount = newAccount(UUID.randomUUID(), clientId, "ACC-2", "EUR");

        when(accountRepository.findByClientIdAndStatus(clientId, "ACTIVE"))
                .thenReturn(List.of(usdAccount, eurAccount));
        when(cashLedgerRepository.sumAmountByAccountId(usdAccount.getAccountId()))
                .thenReturn(new BigDecimal("150.00"));
        when(cashLedgerRepository.sumAmountByAccountId(eurAccount.getAccountId()))
                .thenReturn(new BigDecimal("75.50"));

        BalanceResponse response = balanceService.getBalanceForClient(clientId);

        assertEquals(2, response.accounts().size());
        assertEquals(new BigDecimal("150.00"), response.totalsByCurrency().get("USD"));
        assertEquals(new BigDecimal("75.50"), response.totalsByCurrency().get("EUR"));
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

    private static Account newAccount(UUID accountId, UUID clientId, String accountNumber, String currency) throws Exception {
        var constructor = Account.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Account account = constructor.newInstance();
        setField(account, "accountId", accountId);
        setField(account, "clientId", clientId);
        setField(account, "accountNumber", accountNumber);
        setField(account, "baseCurrency", currency);
        setField(account, "status", "ACTIVE");
        return account;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
