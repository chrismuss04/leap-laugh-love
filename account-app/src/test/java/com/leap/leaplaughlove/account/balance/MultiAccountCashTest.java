package com.leap.leaplaughlove.account.balance;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountAuthorizationService;
import com.leap.leaplaughlove.account.account.AccountRepository;
import com.leap.leaplaughlove.account.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.account.ledger.CashLedgerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises BalanceService and TransferService against a stateful in-memory ledger, so each
 * test can assert what one account's activity does (and does not) do to another's balance.
 */
@DisplayName("Multi-account cash movement Unit Tests")
class MultiAccountCashTest {

    private final UUID clientId = UUID.randomUUID();
    private final UUID otherClientId = UUID.randomUUID();
    private final UUID lowId = new UUID(0L, 1L);
    private final UUID midId = new UUID(0L, 2L);
    private final UUID highId = new UUID(0L, 3L);

    private final Map<UUID, Account> accounts = new LinkedHashMap<>();
    private final List<CashLedgerEntry> ledger = new ArrayList<>();
    private final List<UUID> lockOrder = new ArrayList<>();

    private BalanceService balanceService;
    private TransferService transferService;

    @BeforeEach
    void setUp() {
        addAccount(lowId, clientId, "ACC-LOW", "ACTIVE", "USD");
        addAccount(midId, clientId, "ACC-MID", "ACTIVE", "USD");
        addAccount(highId, clientId, "ACC-HIGH", "ACTIVE", "USD");

        AccountRepository accountRepository = mock(AccountRepository.class);
        when(accountRepository.findByClientIdAndStatus(any(), anyString())).thenAnswer(invocation -> {
            UUID owner = invocation.getArgument(0);
            String status = invocation.getArgument(1);
            return accounts.values().stream()
                    .filter(a -> a.getClientId().equals(owner) && a.getStatus().equals(status))
                    .toList();
        });
        when(accountRepository.findByAccountIdAndClientIdForUpdate(any(), any())).thenAnswer(invocation -> {
            UUID accountId = invocation.getArgument(0);
            UUID owner = invocation.getArgument(1);
            lockOrder.add(accountId);
            return Optional.ofNullable(accounts.get(accountId)).filter(a -> a.getClientId().equals(owner));
        });

        CashLedgerRepository cashLedgerRepository = mock(CashLedgerRepository.class);
        when(cashLedgerRepository.save(any(CashLedgerEntry.class))).thenAnswer(invocation -> {
            CashLedgerEntry entry = invocation.getArgument(0);
            ReflectionTestUtils.setField(entry, "cashLedgerId", UUID.randomUUID());
            ledger.add(entry);
            return entry;
        });
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(any(), anyString())).thenAnswer(invocation -> {
            UUID accountId = invocation.getArgument(0);
            String currency = invocation.getArgument(1);
            return ledger.stream()
                    .filter(e -> e.getAccountId().equals(accountId) && e.getCurrency().equals(currency))
                    .map(CashLedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        });
        when(cashLedgerRepository.sumAmountsByAccountIds(anyList())).thenAnswer(invocation -> {
            List<UUID> ids = invocation.getArgument(0);
            Map<String, BigDecimal> totals = new LinkedHashMap<>();
            ledger.stream().filter(e -> ids.contains(e.getAccountId()))
                    .forEach(e -> totals.merge(e.getAccountId() + "|" + e.getCurrency(), e.getAmount(), BigDecimal::add));
            List<CashLedgerRepository.AccountTotal> result = new ArrayList<>();
            totals.forEach((key, total) -> {
                String[] parts = key.split("\\|");
                result.add(new CashLedgerRepository.AccountTotal() {
                    public UUID getAccountId() { return UUID.fromString(parts[0]); }
                    public String getCurrency() { return parts[1]; }
                    public BigDecimal getTotal() { return total; }
                });
            });
            return result;
        });

        AccountAuthorizationService authorizationService = new AccountAuthorizationService(accountRepository);
        balanceService = new BalanceService(accountRepository, cashLedgerRepository, authorizationService);
        transferService = new TransferService(authorizationService, cashLedgerRepository, balanceService);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(clientId, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void addAccount(UUID accountId, UUID owner, String number, String status, String currency) {
        accounts.put(accountId, new Account(accountId, owner, number, status, currency, true, OffsetDateTime.now()));
    }

    private void deposit(UUID accountId, String amount) {
        balanceService.deposit(accountId, new CashMovementRequest(new BigDecimal(amount), null));
    }

    private CashTransferRequest transferRequest(UUID from, UUID to, String amount) {
        return new CashTransferRequest(from, to, new BigDecimal(amount), null);
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private BigDecimal balanceOf(UUID accountId) {
        return balanceService.getBalanceForClient().accounts().stream()
                .filter(b -> b.accountId().equals(accountId))
                .findFirst().orElseThrow().balance();
    }

    @Test
    @DisplayName("a client with several accounts sees each one with its own balance")
    void clientOwnsMultipleAccountsWithIndependentBalances() {
        deposit(lowId, "500.00");
        deposit(midId, "200.00");

        BalanceResponse response = balanceService.getBalanceForClient();

        assertEquals(3, response.accounts().size());
        assertAmount("500.00", balanceOf(lowId));
        assertAmount("200.00", balanceOf(midId));
        assertAmount("0.00", balanceOf(highId));
        assertAmount("700.00", response.totalByCurrency().get("USD"));
    }

    @Test
    @DisplayName("deposit into one account leaves the other accounts unchanged")
    void depositIsIndependent() {
        deposit(lowId, "100.00");
        deposit(midId, "40.00");

        deposit(lowId, "25.00");

        assertAmount("125.00", balanceOf(lowId));
        assertAmount("40.00", balanceOf(midId));
        assertAmount("0.00", balanceOf(highId));
    }

    @Test
    @DisplayName("withdrawal from one account leaves the other accounts unchanged")
    void withdrawalIsIndependent() {
        deposit(lowId, "100.00");
        deposit(midId, "100.00");

        balanceService.withdraw(lowId, new CashMovementRequest(new BigDecimal("30.00"), null));

        assertAmount("70.00", balanceOf(lowId));
        assertAmount("100.00", balanceOf(midId));
    }

    @Test
    @DisplayName("an account cannot be overdrawn using the funds held in a sibling account")
    void withdrawalCannotBorrowFromSiblingAccount() {
        deposit(lowId, "10.00");
        deposit(midId, "1000.00");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> balanceService.withdraw(lowId, new CashMovementRequest(new BigDecimal("50.00"), null)));

        assertEquals(400, ex.getStatusCode().value());
        assertAmount("10.00", balanceOf(lowId));
        assertAmount("1000.00", balanceOf(midId));
    }

    @Test
    @DisplayName("transfer updates the balances of both the source and destination accounts")
    void transferUpdatesBothBalances() {
        deposit(lowId, "500.00");
        deposit(midId, "100.00");

        CashTransferResponse response = transferService.transfer(transferRequest(lowId, midId, "150.00"));

        assertAmount("350.00", response.fromBalanceAfter());
        assertAmount("250.00", response.toBalanceAfter());
        assertAmount("350.00", balanceOf(lowId));
        assertAmount("250.00", balanceOf(midId));
        assertAmount("0.00", balanceOf(highId));
        assertAmount("600.00", balanceService.getBalanceForClient().totalByCurrency().get("USD"));
    }

    @Test
    @DisplayName("transfer books a WITHDRAWAL on the source and a DEPOSIT on the destination")
    void transferBooksBothLedgerLegs() {
        deposit(lowId, "500.00");
        int rowsBefore = ledger.size();

        CashTransferResponse response = transferService.transfer(
                new CashTransferRequest(lowId, midId, new BigDecimal("120.00"), "rent"));

        assertEquals(rowsBefore + 2, ledger.size());
        CashLedgerEntry withdrawal = ledger.get(rowsBefore);
        CashLedgerEntry deposit = ledger.get(rowsBefore + 1);

        assertEquals("WITHDRAWAL", withdrawal.getEntryType());
        assertEquals(lowId, withdrawal.getAccountId());
        assertAmount("-120.00", withdrawal.getAmount());
        assertEquals("Transfer to ACC-MID - rent", withdrawal.getDescription());

        assertEquals("DEPOSIT", deposit.getEntryType());
        assertEquals(midId, deposit.getAccountId());
        assertAmount("120.00", deposit.getAmount());
        assertEquals("Transfer from ACC-LOW - rent", deposit.getDescription());

        assertEquals(withdrawal.getCashLedgerId(), response.transferId());
        assertEquals(lowId, response.fromAccountId());
        assertEquals(midId, response.toAccountId());
        assertAmount("120.00", response.amount());
        assertEquals("USD", response.currency());
    }

    @Test
    @DisplayName("transfer of the full balance leaves the source at zero")
    void transferFullBalance() {
        deposit(lowId, "75.50");

        CashTransferResponse response = transferService.transfer(transferRequest(lowId, midId, "75.50"));

        assertAmount("0.00", response.fromBalanceAfter());
        assertAmount("75.50", response.toBalanceAfter());
        assertAmount("0.00", balanceOf(lowId));
        assertAmount("75.50", balanceOf(midId));
    }

    @Test
    @DisplayName("transfer rejects an amount larger than the source balance and writes nothing")
    void transferRejectsInsufficientFunds() {
        deposit(lowId, "50.00");
        deposit(midId, "900.00");
        int rowsBefore = ledger.size();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> transferService.transfer(transferRequest(lowId, midId, "50.01")));

        assertEquals(400, ex.getStatusCode().value());
        assertEquals("Transfer amount cannot exceed available account balance", ex.getReason());
        assertEquals(rowsBefore, ledger.size());
        assertAmount("50.00", balanceOf(lowId));
        assertAmount("900.00", balanceOf(midId));
    }

    @Test
    @DisplayName("transfer rejects the same account as source and destination")
    void transferRejectsSameAccount() {
        deposit(lowId, "50.00");
        int rowsBefore = ledger.size();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> transferService.transfer(transferRequest(lowId, lowId, "10.00")));

        assertEquals(400, ex.getStatusCode().value());
        assertEquals(rowsBefore, ledger.size());
    }

    @Test
    @DisplayName("transfer rejects a zero or negative amount")
    void transferRejectsNonPositiveAmount() {
        deposit(lowId, "50.00");
        int rowsBefore = ledger.size();

        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> transferService.transfer(transferRequest(lowId, midId, "0.00"))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> transferService.transfer(transferRequest(lowId, midId, "-5.00"))).getStatusCode().value());
        assertEquals(rowsBefore, ledger.size());
    }

    @Test
    @DisplayName("transfer to an account owned by another client is a 404 and writes nothing")
    void transferRejectsAccountOfAnotherClient() {
        UUID foreignId = UUID.randomUUID();
        addAccount(foreignId, otherClientId, "ACC-FOREIGN", "ACTIVE", "USD");
        deposit(lowId, "100.00");
        int rowsBefore = ledger.size();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> transferService.transfer(transferRequest(lowId, foreignId, "10.00")));

        assertEquals(404, ex.getStatusCode().value());
        assertEquals(rowsBefore, ledger.size());
    }

    @Test
    @DisplayName("transfer from an account owned by another client is a 404 and writes nothing")
    void transferRejectsSourceOfAnotherClient() {
        UUID foreignId = UUID.randomUUID();
        addAccount(foreignId, otherClientId, "ACC-FOREIGN", "ACTIVE", "USD");
        int rowsBefore = ledger.size();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> transferService.transfer(transferRequest(foreignId, lowId, "10.00")));

        assertEquals(404, ex.getStatusCode().value());
        assertEquals(rowsBefore, ledger.size());
    }

    @Test
    @DisplayName("transfer involving an inactive account is a 400 and writes nothing")
    void transferRejectsInactiveAccount() {
        UUID pendingId = UUID.randomUUID();
        addAccount(pendingId, clientId, "ACC-PENDING", "PENDING", "USD");
        deposit(lowId, "100.00");
        int rowsBefore = ledger.size();

        ResponseStatusException toInactive = assertThrows(ResponseStatusException.class,
                () -> transferService.transfer(transferRequest(lowId, pendingId, "10.00")));
        ResponseStatusException fromInactive = assertThrows(ResponseStatusException.class,
                () -> transferService.transfer(transferRequest(pendingId, lowId, "10.00")));

        assertEquals(400, toInactive.getStatusCode().value());
        assertEquals("Account is not active", toInactive.getReason());
        assertEquals(400, fromInactive.getStatusCode().value());
        assertEquals(rowsBefore, ledger.size());
    }

    @Test
    @DisplayName("transfer between accounts with different currencies is a 400 and writes nothing")
    void transferRejectsCurrencyMismatch() {
        UUID eurId = UUID.randomUUID();
        addAccount(eurId, clientId, "ACC-EUR", "ACTIVE", "EUR");
        deposit(lowId, "100.00");
        int rowsBefore = ledger.size();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> transferService.transfer(transferRequest(lowId, eurId, "10.00")));

        assertEquals(400, ex.getStatusCode().value());
        assertEquals(rowsBefore, ledger.size());
    }

    @Test
    @DisplayName("transfer locks both accounts in ascending id order regardless of direction")
    void transferLocksAccountsInAscendingOrder() {
        deposit(highId, "100.00");
        deposit(lowId, "100.00");
        lockOrder.clear();

        transferService.transfer(transferRequest(highId, lowId, "10.00"));
        assertEquals(List.of(lowId, highId), lockOrder);

        lockOrder.clear();
        transferService.transfer(transferRequest(lowId, highId, "10.00"));
        assertEquals(List.of(lowId, highId), lockOrder);
    }

    @Test
    @DisplayName("transfer does not touch a third account the client owns")
    void transferLeavesOtherAccountsUntouched() {
        deposit(lowId, "100.00");
        deposit(highId, "33.00");
        long highRowsBefore = ledger.stream().filter(e -> e.getAccountId().equals(highId)).count();

        transferService.transfer(transferRequest(lowId, midId, "40.00"));

        assertAmount("33.00", balanceOf(highId));
        assertEquals(highRowsBefore, ledger.stream().filter(e -> e.getAccountId().equals(highId)).count());
    }

    @Test
    @DisplayName("consecutive transfers are each reflected in both balances")
    void repeatedTransfersAccumulate() {
        deposit(lowId, "300.00");

        CashTransferResponse first = transferService.transfer(transferRequest(lowId, midId, "100.00"));
        CashTransferResponse second = transferService.transfer(transferRequest(midId, lowId, "40.00"));

        assertNotEquals(first.transferId(), second.transferId());
        assertAmount("240.00", second.toBalanceAfter());
        assertAmount("60.00", second.fromBalanceAfter());
        assertAmount("240.00", balanceOf(lowId));
        assertAmount("60.00", balanceOf(midId));
        assertTrue(ledger.stream().map(CashLedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(new BigDecimal("300.00")) == 0, "transfers must not create or destroy cash");
    }
}
