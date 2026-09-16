package com.leap.leaplaughlove.trading.balance;

import com.leap.leaplaughlove.common.security.SecurityUtils;
import com.leap.leaplaughlove.trading.account.Account;
import com.leap.leaplaughlove.trading.account.AccountAuthorizationService;
import com.leap.leaplaughlove.trading.account.AccountRepository;
import com.leap.leaplaughlove.trading.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.trading.ledger.CashLedgerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for handling balance-related operations such as retrieving account balances,
 * performing deposits, and executing withdrawals.
 * Provides methods for calculating totals by currency and ensuring account authorization.
 */
@Service
public class BalanceService {

    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String ENTRY_TYPE_DEPOSIT = "DEPOSIT";
    private static final String ENTRY_TYPE_WITHDRAWAL = "WITHDRAWAL";

    private final AccountRepository accountRepository;
    private final CashLedgerRepository cashLedgerRepository;
    private final AccountAuthorizationService accountAuthorizationService;

    /**
     * Constructs a new BalanceService with the specified repositories and authorization service.
     */
    @Autowired
    public BalanceService(AccountRepository accountRepository,
                          CashLedgerRepository cashLedgerRepository,
                          AccountAuthorizationService accountAuthorizationService) {
        this.accountRepository = accountRepository;
        this.cashLedgerRepository = cashLedgerRepository;
        this.accountAuthorizationService = accountAuthorizationService != null
                ? accountAuthorizationService
                : new AccountAuthorizationService(accountRepository);
    }

    public BalanceService(AccountRepository accountRepository, CashLedgerRepository cashLedgerRepository) {
        this(accountRepository, cashLedgerRepository, new AccountAuthorizationService(accountRepository));
    }

    /**
     * Retrieves the balance information for the authenticated client.
     * @return the balance response containing account balances and totals by currency
     */
    public BalanceResponse getBalanceForClient() {
        UUID clientId = SecurityUtils.getAuthenticatedClientId();
        List<Account> accounts = accountRepository.findByClientIdAndStatus(clientId, ACTIVE_STATUS);
        List<UUID> accountIds = accounts.stream().map(Account::getAccountId).toList();

        Map<AccountCurrencyKey, BigDecimal> totalsByAccountAndCurrency = accountIds.isEmpty()
                ? Map.of()
                : cashLedgerRepository.sumAmountsByAccountIds(accountIds).stream()
                        .collect(Collectors.toMap(
                                total -> new AccountCurrencyKey(total.getAccountId(), total.getCurrency()),
                                CashLedgerRepository.AccountTotal::getTotal));

        List<AccountBalance> balances = accounts.stream()
                .map(account -> new AccountBalance(
                        account.getAccountId(),
                        account.getAccountNumber(),
                        account.getBaseCurrency(),
                        totalsByAccountAndCurrency.getOrDefault(
                                new AccountCurrencyKey(account.getAccountId(), account.getBaseCurrency()),
                                BigDecimal.ZERO)))
                .toList();

        Map<String, BigDecimal> totalsByCurrency = balances.stream()
                .collect(Collectors.groupingBy(
                        AccountBalance::currency,
                        Collectors.reducing(BigDecimal.ZERO, AccountBalance::balance, BigDecimal::add)));

        return new BalanceResponse(balances, totalsByCurrency);
    }

    /**
     * Deposits the specified amount into the given account
     * @param accountId the unique identifier of the account to deposit into
     * @param request the cash movement request containing the deposit details
     * @return the response containing details of the cash transaction
     */
    @Transactional
    public CashTransactionResponse deposit(UUID accountId, CashMovementRequest request) {
        BigDecimal amount = normalizeAmount(request.amount());
        Account account = accountAuthorizationService.getAuthorizedAccount(accountId);
        BigDecimal currentBalance = getCurrentBalance(account);

        CashLedgerEntry saved = cashLedgerRepository.save(new CashLedgerEntry(
                account.getAccountId(),
                ENTRY_TYPE_DEPOSIT,
                amount,
                account.getBaseCurrency(),
                OffsetDateTime.now(),
                request.description()));

        return toResponse(saved, currentBalance.add(amount));
    }

    /**
     * Withdraws the specified amount from the given account
     * @param accountId the unique identifier of the account to withdraw from
     * @param request the cash movement request containing the withdrawal details
     * @throws ResponseStatusException if the withdrawal amount exceeds available balance
     * @return the response containing details of the cash transaction
     */
    @Transactional
    public CashTransactionResponse withdraw(UUID accountId, CashMovementRequest request) {
        BigDecimal amount = normalizeAmount(request.amount());
        Account account = accountAuthorizationService.getAuthorizedAccount(accountId);
        BigDecimal currentBalance = getCurrentBalance(account);

        if (amount.compareTo(currentBalance) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Withdrawal amount cannot exceed available account balance");
        }

        BigDecimal ledgerAmount = amount.negate();
        CashLedgerEntry saved = cashLedgerRepository.save(new CashLedgerEntry(
                account.getAccountId(),
                ENTRY_TYPE_WITHDRAWAL,
                ledgerAmount,
                account.getBaseCurrency(),
                OffsetDateTime.now(),
                request.description()));

        return toResponse(saved, currentBalance.add(ledgerAmount));
    }

    private BigDecimal getCurrentBalance(Account account) {
        return cashLedgerRepository.sumAmountByAccountIdAndCurrency(account.getAccountId(), account.getBaseCurrency());
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be greater than zero");
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private CashTransactionResponse toResponse(CashLedgerEntry entry, BigDecimal balanceAfter) {
        return new CashTransactionResponse(
                entry.getCashLedgerId(),
                entry.getAccountId(),
                entry.getEntryType(),
                entry.getAmount(),
                entry.getCurrency(),
                balanceAfter,
                entry.getCreatedAt(),
                entry.getDescription());
    }

    private record AccountCurrencyKey(UUID accountId, String currency) {
    }
}
