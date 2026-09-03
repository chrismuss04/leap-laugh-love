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

        public BalanceResponse getBalanceForClient() {
                UUID clientId = getAuthenticatedClientId();
        List<Account> accounts = accountRepository.findByClientIdAndStatus(clientId, ACTIVE_STATUS);
        List<UUID> accountIds = accounts.stream().map(Account::getAccountId).toList();

        // Keyed by (accountId, currency) — an account should only be credited for entries in its own currency.
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

        @Transactional
        public CashTransactionResponse deposit(UUID accountId, CashMovementRequest request) {
                BigDecimal amount = normalizeAmount(request.amount());
                Account account = getAuthorizedAccount(accountId);
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

        @Transactional
        public CashTransactionResponse withdraw(UUID accountId, CashMovementRequest request) {
                BigDecimal amount = normalizeAmount(request.amount());
                Account account = getAuthorizedAccount(accountId);
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

        // use accountRepository to check if client owns account - authorization check
        private Account getAuthorizedAccount(UUID accountId) {
                UUID clientId = getAuthenticatedClientId();
                Account account = accountRepository.findById(accountId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

                if (!ACTIVE_STATUS.equals(account.getStatus())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account is not active");
                }
                if (!account.getClientId().equals(clientId)) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                        "Authenticated user is not permitted to modify this account");
                }
                return account;
        }

        // use spring security to get ClientID - ensures the user is authenticated
        private UUID getAuthenticatedClientId() {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication == null || !authentication.isAuthenticated()) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                        "A valid authenticated principal is required");
                }

                Object principal = authentication.getPrincipal();
                if (principal instanceof UUID clientId) {
                        return clientId;
                }
                if (principal instanceof String principalString && !principalString.isBlank()
                                && !"anonymousUser".equals(principalString)) {
                        try {
                                return UUID.fromString(principalString);
                        } catch (IllegalArgumentException ignored) {
                                // Fall through to unauthorized below.
                        }
                }

                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                "Authenticated principal is invalid for this operation");
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
