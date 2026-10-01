package com.leap.leaplaughlove.account.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@Sql(scripts = "/db/positions_test_setup.sql")
@DisplayName("AccountRepository.findEmptySince")
class EmptyAccountQueryIntegrationTest {

    private static final UUID CLIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AAPL = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001");
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);

    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID account(String status, int openedDaysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO trading.accounts (account_id, client_id, account_number, status, base_currency, "
                + "trading_enabled, created_at) VALUES (?, ?, ?, ?, 'USD', TRUE, ?)",
                id, CLIENT_ID, "ACC-" + id, status, NOW.minusDays(openedDaysAgo));
        return id;
    }

    private void ledger(UUID accountId, String type, String amount, String currency, int daysAgo) {
        jdbc.update("INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, entry_type, amount, currency, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), accountId, type, new java.math.BigDecimal(amount),
                currency, NOW.minusDays(daysAgo));
    }

    private void position(UUID accountId, long quantity) {
        jdbc.update("INSERT INTO trading.positions (account_id, instrument_id, quantity, avg_cost) VALUES (?, ?, ?, 0)",
                accountId, AAPL, quantity);
    }

    private Map<UUID, OffsetDateTime> emptyForOverAMonth() {
        return accountRepository.findEmptySince(NOW.minusDays(30)).stream().collect(Collectors.toMap(
                AccountRepository.EmptyAccount::getAccountId, a -> a.getEmptySince().withOffsetSameInstant(ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("finds an account emptied over 30 days ago, dated from its last ledger entry")
    void findsAccountEmptiedOverAMonthAgo() {
        UUID emptied = account("ACTIVE", 60);
        ledger(emptied, "DEPOSIT", "100.00", "USD", 50);
        ledger(emptied, "WITHDRAWAL", "-100.00", "USD", 40);

        assertThat(emptyForOverAMonth()).containsEntry(emptied, NOW.minusDays(40));
    }

    @Test
    @DisplayName("finds a never-funded account opened over 30 days ago, dated from when it was opened")
    void findsNeverFundedAccount() {
        UUID neverFunded = account("ACTIVE", 45);

        assertThat(emptyForOverAMonth()).containsEntry(neverFunded, NOW.minusDays(45));
    }

    @Test
    @DisplayName("finds an account that sold all its shares, since a zero-quantity position holds nothing")
    void findsAccountWithOnlyClosedPositions() {
        UUID soldOut = account("ACTIVE", 60);
        ledger(soldOut, "DEPOSIT", "100.00", "USD", 50);
        ledger(soldOut, "BUY_SETTLEMENT", "-100.00", "USD", 45);
        position(soldOut, 0);

        assertThat(emptyForOverAMonth()).containsEntry(soldOut, NOW.minusDays(45));
    }

    @Test
    @DisplayName("skips accounts that are funded, recently emptied, recently opened, holding shares or not active")
    void skipsAccountsThatDoNotQualify() {
        UUID funded = account("ACTIVE", 60);
        ledger(funded, "DEPOSIT", "100.00", "USD", 50);

        UUID recentlyEmptied = account("ACTIVE", 60);
        ledger(recentlyEmptied, "DEPOSIT", "100.00", "USD", 50);
        ledger(recentlyEmptied, "WITHDRAWAL", "-100.00", "USD", 10);

        UUID recentlyOpened = account("ACTIVE", 5);

        UUID holdingShares = account("ACTIVE", 60);
        ledger(holdingShares, "DEPOSIT", "100.00", "USD", 50);
        ledger(holdingShares, "BUY_SETTLEMENT", "-100.00", "USD", 40);
        position(holdingShares, 10);

        UUID closed = account("CLOSED", 60);

        UUID offsettingCurrencies = account("ACTIVE", 60);
        ledger(offsettingCurrencies, "DEPOSIT", "100.00", "USD", 50);
        ledger(offsettingCurrencies, "ADJUSTMENT", "-100.00", "EUR", 40);

        assertThat(emptyForOverAMonth()).doesNotContainKeys(
                funded, recentlyEmptied, recentlyOpened, holdingShares, closed, offsettingCurrencies);
    }
}
