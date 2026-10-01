package com.leap.leaplaughlove.account.inactivity;

import com.leap.leaplaughlove.account.account.AccountRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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
    private static final UUID FUNDED_SEED_ACCOUNT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);

    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private InactiveAccountDetector detector;

    @BeforeEach
    void setUp() {
        detector = new InactiveAccountDetector(accountRepository, 30, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    private UUID account(String status, int openedDaysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO trading.accounts (account_id, client_id, account_number, status, base_currency, "
                + "trading_enabled, created_at) VALUES (?, ?, ?, ?, 'USD', TRUE, ?)",
                id, CLIENT_ID, "ACC-" + id, status, NOW.minusDays(openedDaysAgo));
        return id;
    }

    private void ledger(UUID accountId, String type, String amount, int daysAgo) {
        jdbc.update("INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, entry_type, amount, currency, created_at) "
                + "VALUES (?, ?, ?, ?, 'USD', ?)", UUID.randomUUID(), accountId, type, new BigDecimal(amount),
                NOW.minusDays(daysAgo));
    }

    /** Runs the job, writes its changes, and reads each account's flag back from the table. */
    private OffsetDateTime runAndReadFlag(UUID accountId) {
        detector.detect();
        entityManager.flush();
        entityManager.clear();
        return inactiveSince(accountId);
    }

    private OffsetDateTime inactiveSince(UUID accountId) {
        OffsetDateTime value = jdbc.queryForObject("SELECT inactive_since FROM trading.accounts WHERE account_id = ?",
                OffsetDateTime.class, accountId);
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("flags an account empty for over 30 days with the date it became empty")
    void flagsAccountEmptyOverAMonth() {
        UUID emptied = account("ACTIVE", 60);
        ledger(emptied, "DEPOSIT", "100.00", 50);
        ledger(emptied, "WITHDRAWAL", "-100.00", 40);

        assertThat(runAndReadFlag(emptied)).isEqualTo(NOW.minusDays(40));
        assertThat(inactiveSince(FUNDED_SEED_ACCOUNT)).isNull();
    }

    @Test
    @DisplayName("leaves an account empty for 30 days or less unflagged")
    void leavesRecentlyEmptiedAccount() {
        UUID recent = account("ACTIVE", 60);
        ledger(recent, "DEPOSIT", "100.00", 50);
        ledger(recent, "WITHDRAWAL", "-100.00", 29);

        assertThat(runAndReadFlag(recent)).isNull();
    }

    @Test
    @DisplayName("clears the flag once the account is funded again")
    void clearsFlagAfterDeposit() {
        UUID account = account("ACTIVE", 45);
        assertThat(runAndReadFlag(account)).isEqualTo(NOW.minusDays(45));

        ledger(account, "DEPOSIT", "50.00", 0);

        assertThat(runAndReadFlag(account)).isNull();
    }

    @Test
    @DisplayName("clears the flag on an account that is no longer active")
    void clearsFlagOnClosedAccount() {
        UUID account = account("ACTIVE", 45);
        assertThat(runAndReadFlag(account)).isNotNull();

        jdbc.update("UPDATE trading.accounts SET status = 'CLOSED' WHERE account_id = ?", account);

        assertThat(runAndReadFlag(account)).isNull();
    }

    @Test
    @DisplayName("gives the same result when run again")
    void rerunChangesNothing() {
        UUID account = account("ACTIVE", 45);
        OffsetDateTime first = runAndReadFlag(account);

        assertThat(runAndReadFlag(account)).isEqualTo(first).isEqualTo(NOW.minusDays(45));
    }
}
