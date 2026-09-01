package com.leap.leaplaughlove.balance;

import com.leap.leaplaughlove.trading.Account;
import com.leap.leaplaughlove.trading.AccountRepository;
import com.leap.leaplaughlove.trading.CashLedgerRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BalanceService {

    private static final String ACTIVE_STATUS = "ACTIVE";

    private final AccountRepository accountRepository;
    private final CashLedgerRepository cashLedgerRepository;

    public BalanceService(AccountRepository accountRepository, CashLedgerRepository cashLedgerRepository) {
        this.accountRepository = accountRepository;
        this.cashLedgerRepository = cashLedgerRepository;
    }

    public BalanceResponse getBalanceForClient(UUID clientId) {
        List<Account> accounts = accountRepository.findByClientIdAndStatus(clientId, ACTIVE_STATUS);

        List<AccountBalance> balances = accounts.stream()
                .map(account -> new AccountBalance(
                        account.getAccountId(),
                        account.getAccountNumber(),
                        account.getBaseCurrency(),
                        cashLedgerRepository.sumAmountByAccountId(account.getAccountId())))
                .toList();

        Map<String, BigDecimal> totalsByCurrency = balances.stream()
                .collect(Collectors.groupingBy(
                        AccountBalance::currency,
                        Collectors.reducing(BigDecimal.ZERO, AccountBalance::balance, BigDecimal::add)));

        return new BalanceResponse(balances, totalsByCurrency);
    }
}
