package com.leap.leaplaughlove.balance;

import com.leap.leaplaughlove.trading.Account;
import com.leap.leaplaughlove.trading.AccountRepository;
import com.leap.leaplaughlove.trading.CashLedgerEntry;
import com.leap.leaplaughlove.trading.CashLedgerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BalanceService {

    private static final String ACTIVE_STATUS = "ACTIVE";
        private static final String ENTRY_TYPE_DEPOSIT = "DEPOSIT";
        private static final String ENTRY_TYPE_WITHDRAWAL = "WITHDRAWAL";

    private final AccountRepository accountRepository;
    private final CashLedgerRepository cashLedgerRepository;

    public BalanceService(AccountRepository accountRepository, CashLedgerRepository cashLedgerRepository) {
        this.accountRepository = accountRepository;
        this.cashLedgerRepository = cashLedgerRepository;
    }

        /**
         * Retrieves the balances for each of the authenticated client's accounts.
         * @param void
         * @return BalanceResponse containing the balances for each account and the totals by currency.
         */
        public BalanceResponse getBalanceForClient() {
                UUID clientId = getAuthenticatedClientId();
                List<Account> accounts = accountRepository.findByClientIdAndStatus(clientId, ACTIVE_STATUS);
                List<UUID> accountIds = accounts.stream().map(Account::getAccountId).toList();

                // keyed by (accountId, currency) — an account should only be credited for entries in its own currency
                Map<AccountCurrencyKey, BigDecimal> totalsByAccountAndCurrency = accountIds.isEmpty()
                        ? Map.of()
                        : cashLedgerRepository.sumAmountsByAccountIds(accountIds).stream()
                                .collect(Collectors.toMap(
                                        total -> new AccountCurrencyKey(total.getAccountId(), total.getCurrency()),
                                        CashLedgerRepository.AccountTotal::getTotal));
                // collect total balances by account and currency
                List<AccountBalance> balances = accounts.stream()
                        .map(account -> new AccountBalance(
                                account.getAccountId(),
                                account.getAccountNumber(),
                                account.getBaseCurrency(),
                                totalsByAccountAndCurrency.getOrDefault(
                                        new AccountCurrencyKey(account.getAccountId(), account.getBaseCurrency()),
                                BigDecimal.ZERO)))
                        .toList();
                // collect total balances by currency
                Map<String, BigDecimal> totalsByCurrency = balances.stream()
                        .collect(Collectors.groupingBy(
                                AccountBalance::currency,
                                Collectors.reducing(BigDecimal.ZERO, AccountBalance::balance, BigDecimal::add)));

                return new BalanceResponse(balances, totalsByCurrency);
        }

        /**
         * Deposits the specified amount into the given account.
         * @param accountId
         * @param request
         * @return CashTransactionResponse containing the details of the deposit transaction.
         */
        @Transactional
        public CashTransactionResponse deposit(UUID accountId, CashMovementRequest request) {
                BigDecimal amount = normalizeAmount(request.amount());
                Account account = getAuthorizedAccount(accountId);
                BigDecimal currentBalance = getCurrentBalance(account);

                // create a new cash ledger entry for the deposit and saves it on the ledger
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
         * @param accountId
         * @param request
         * @return CashTransactionResponse containing the details of the withdrawal transaction.
         */
        @Transactional
        public CashTransactionResponse withdraw(UUID accountId, CashMovementRequest request) {
                BigDecimal amount = normalizeAmount(request.amount());
                Account account = getAuthorizedAccount(accountId);
                BigDecimal currentBalance = getCurrentBalance(account);
                // if the withdrawal amount exceeds the current balance, throw an exception
                if (amount.compareTo(currentBalance) > 0) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Withdrawal amount cannot exceed available account balance");
                }
                // create a new cash ledger entry for the withdrawal and saves it on the ledger
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
         * Retrieves the account with the given ID if it belongs to the authenticated client and is active.
         * @param accountId 
         * @throws ResponseStatusException if the account is not found, not active, or the authenticated client is not authorized to access it
         * @return the authorized and active Account object
         */
        // use accountRepository to check if client owns account - authorization check
        private Account getAuthorizedAccount(UUID accountId) {
                UUID clientId = getAuthenticatedClientId();
                // if not found, throw error
                Account account = accountRepository.findById(accountId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
                //if not active, throw error
                if (!ACTIVE_STATUS.equals(account.getStatus())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account is not active");
                }
                // if the authenticated client does not own the account, throw error (unauthorized)
                if (!account.getClientId().equals(clientId)) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                        "Authenticated user is not permitted to modify this account");
                }
                return account;
        }
        /**
         * retrieves the authenticated client's ID from the Spring Security context.
         * @throws ResponseStatusException if the client is not authenticated or the principal is invalid
         * @return the UUID of the authenticated client
         */
        private UUID getAuthenticatedClientId() {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                // if authentication is null or invalid, throw error
                if (authentication == null || !authentication.isAuthenticated()) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                        "A valid authenticated principal is required");
                }
                // extract the principal from the authentication object
                Object principal = authentication.getPrincipal();
                if (principal instanceof UUID clientId) {
                        return clientId;
                }
                // if the principal is a UUID, return it directly
                if (principal instanceof String principalString && !principalString.isBlank()
                                && !"anonymousUser".equals(principalString)) {
                        try {
                                return UUID.fromString(principalString);
                        } catch (IllegalArgumentException ignored) {
                                // Fall through to unauthorized below.
                        }
                }
                // if all attempts to extract a valid UUID fail, throw unauthorized error
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                "Authenticated principal is invalid for this operation");
        }
        /**
         * calculates the current balance for the given account by summing the cash ledger entries.
         * @param account
         * @return the current balance of the account
         */
        private BigDecimal getCurrentBalance(Account account) {
                return cashLedgerRepository.sumAmountByAccountIdAndCurrency(account.getAccountId(), account.getBaseCurrency());
        }

        /**
         * checks if the given amount is valid and normalizes it to two decimal places.
         * @param amount
         * @return amount - normalized amount with two decimal places
         */
        private BigDecimal normalizeAmount(BigDecimal amount) {
                if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be greater than zero");
                }
                return amount.setScale(2, java.math.RoundingMode.HALF_UP);
        }

        /**
         * builds the CashTransactionResponse payload from given ledger entry and the resulting balance after the transaction.
         * @param entry
         * @param balanceAfter
         * @return the constructed CashTransactionResponse object
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

    private record AccountCurrencyKey(UUID accountId, String currency) {
    }
}
