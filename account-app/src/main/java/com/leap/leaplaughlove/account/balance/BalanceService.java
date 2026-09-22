package com.leap.leaplaughlove.account.balance;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountAuthorizationService;
import com.leap.leaplaughlove.account.account.AccountRepository;
import com.leap.leaplaughlove.account.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.account.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.common.security.SecurityUtils;
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
 * Service for handling balance-related operations such as retrieving account balances, performing deposits, and executing withdrawals.
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
     * Constructs a new BalanceService with the injected repositories and authorization service.
     * @param accountRepository the repository for accessing account data
     * @param cashLedgerRepository the repository for accessing cash ledger entries
     * @param accountAuthorizationService the service for handling account authorization
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
     * @return a BalanceResponse containing the individual account balances and the total balance by currency
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
     * Deposits the specified amount into the given account.
     * @param accountId the unique identifier of the account
     * @param request the cash movement request containing deposit details
     * @return a CashTransactionResponse containing the result of the deposit transaction
     * @throws IllegalArgumentException if the deposit request is invalid
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
     * Withdraws the specified amount from the given account.
     * @param accountId the unique identifier of the account
     * @param request the cash movement request containing withdrawal details
     * @return a CashTransactionResponse containing the result of the withdrawal transaction
     * @throws IllegalArgumentException if the withdrawal request is invalid
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

    /**
     * Retrieves the current balance for the specified account.
     * @param account the account for which to retrieve the current balance
     * @return the current balance of the specified account
     */
    public BigDecimal getCurrentBalance(Account account) {
        return cashLedgerRepository.sumAmountByAccountIdAndCurrency(account.getAccountId(), account.getBaseCurrency());
    }

    /**
     * Normalizes the specified amount by ensuring it is greater than zero and rounding it to two decimal places.
     * @param amount the amount to be normalized
     * @return the normalized amount
     * @throws ResponseStatusException if the amount is null or less than or equal to zero
     */
    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be greater than zero");
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Converts a cash ledger entry and the resulting balance into a cash transaction response.
     * @param entry the cash ledger entry to be converted
     * @param balanceAfter the resulting balance after the transaction
     * @return the corresponding cash transaction response
     */
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

    /**
     * Represents a composite key consisting of an account ID and a currency.
     * @param accountId the unique identifier of the account
     * @param currency the currency associated with the account
     */
    private record AccountCurrencyKey(UUID accountId, String currency) {
    }
}

