package com.leap.leaplaughlove.account.inactivity;

import com.leap.leaplaughlove.account.account.AccountRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@Sql(scripts = "/db/positions_test_setup.sql")
@DisplayName("InactiveAccountDetector")
class InactiveAccountDetectorIntegrationTest {

    private static final UUID CLIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AAPL = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001");
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);

    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private UUID account(String status, int openedDaysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO trading.accounts (account_id, client_id, account_number, status, base_currency, "
                + "trading_enabled, created_at) VALUES (?, ?, ?, ?, 'USD', TRUE, ?)",
                id, CLIENT_ID, "ACC-" + id, status, NOW.minusDays(openedDaysAgo));
        return id;
    }

    private void ledger(UUID accountId, String amount, String currency, int daysAgo) {
        jdbc.update("INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, entry_type, amount, currency, created_at) "
                + "VALUES (?, ?, 'ADJUSTMENT', ?, ?, ?)", UUID.randomUUID(), accountId, new BigDecimal(amount),
                currency, NOW.minusDays(daysAgo));
    }

    private void position(UUID accountId, long quantity) {
        jdbc.update("INSERT INTO trading.positions (account_id, instrument_id, quantity, avg_cost) VALUES (?, ?, ?, 0)",
                accountId, AAPL, quantity);
    }

    private void runJob() {
        new InactiveAccountDetector(accountRepository, 30, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)).detect();
        entityManager.flush();
        entityManager.clear();
    }

    private OffsetDateTime inactiveSince(UUID accountId) {
        OffsetDateTime value = jdbc.queryForObject("SELECT inactive_since FROM trading.accounts WHERE account_id = ?",
                OffsetDateTime.class, accountId);
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("flags accounts empty for over 30 days, dated from their last ledger entry or opening")
    void flagsAccountsEmptyOverAMonth() {
        UUID emptied = account("ACTIVE", 60);
        ledger(emptied, "100.00", "USD", 50);
        ledger(emptied, "-100.00", "USD", 40);
        UUID neverFunded = account("ACTIVE", 45);
        UUID soldOut = account("ACTIVE", 60);
        position(soldOut, 0);

        runJob();

        assertThat(inactiveSince(emptied)).isEqualTo(NOW.minusDays(40));
        assertThat(inactiveSince(neverFunded)).isEqualTo(NOW.minusDays(45));
        assertThat(inactiveSince(soldOut)).isEqualTo(NOW.minusDays(60));
    }

    @Test
    @DisplayName("skips accounts that are funded, recently emptied or opened, holding shares or not active")
    void skipsAccountsThatDoNotQualify() {
        UUID funded = account("ACTIVE", 60);
        ledger(funded, "100.00", "USD", 50);
        UUID recentlyEmptied = account("ACTIVE", 60);
        ledger(recentlyEmptied, "100.00", "USD", 50);
        ledger(recentlyEmptied, "-100.00", "USD", 29);
        UUID recentlyOpened = account("ACTIVE", 5);
        UUID holdingShares = account("ACTIVE", 60);
        position(holdingShares, 10);
        UUID closed = account("CLOSED", 60);
        UUID offsettingCurrencies = account("ACTIVE", 60);
        ledger(offsettingCurrencies, "100.00", "USD", 50);
        ledger(offsettingCurrencies, "-100.00", "EUR", 40);

        runJob();

        for (UUID id : new UUID[] {funded, recentlyEmptied, recentlyOpened, holdingShares, closed, offsettingCurrencies}) {
            assertThat(inactiveSince(id)).as(id.toString()).isNull();
        }
    }

    @Test
    @DisplayName("keeps the flag on rerun and clears it once the account is funded or closed")
    void clearsFlagWhenNoLongerInactive() {
        UUID funded = account("ACTIVE", 45);
        UUID closed = account("ACTIVE", 45);
        runJob();
        runJob();
        assertThat(inactiveSince(funded)).isEqualTo(NOW.minusDays(45));

        ledger(funded, "50.00", "USD", 0);
        jdbc.update("UPDATE trading.accounts SET status = 'CLOSED' WHERE account_id = ?", closed);
        runJob();

        assertThat(inactiveSince(funded)).isNull();
        assertThat(inactiveSince(closed)).isNull();
    }
}
