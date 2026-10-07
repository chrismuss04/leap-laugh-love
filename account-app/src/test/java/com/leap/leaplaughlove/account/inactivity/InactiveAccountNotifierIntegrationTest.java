package com.leap.leaplaughlove.account.inactivity;

import com.leap.leaplaughlove.account.account.AccountRepository;
import com.leap.leaplaughlove.account.client.ClientStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Not @Transactional: the notifier marks each account in a transaction of its own, which would not
 * see rows left uncommitted by a test transaction. The setup script recreates the tables per test.
 */
@SpringBootTest
@Sql(scripts = "/db/positions_test_setup.sql")
@DisplayName("InactiveAccountNotifier")
class InactiveAccountNotifierIntegrationTest {

    private static final UUID CLIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 6, 2, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private AccountRepository accountRepository;
    @Autowired private ClientStatusRepository clientStatusRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private JavaMailSender mailSender;
    private InactiveAccountNotifier notifier;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        notifier = new InactiveAccountNotifier(accountRepository, clientStatusRepository, mailSender,
                transactionManager, "no-reply@example.com", Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    private UUID flaggedAccount(UUID clientId, String accountNumber, OffsetDateTime inactiveSince) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO trading.accounts (account_id, client_id, account_number, status, base_currency, "
                + "trading_enabled, created_at, inactive_since) VALUES (?, ?, ?, 'ACTIVE', 'USD', TRUE, ?, ?)",
                id, clientId, accountNumber, inactiveSince, inactiveSince);
        return id;
    }

    private UUID client(String email, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO iam.clients (client_id, email, status) VALUES (?, ?, ?)", id, email, status);
        return id;
    }

    private OffsetDateTime notifiedAt(UUID accountId) {
        OffsetDateTime value = jdbc.queryForObject(
                "SELECT inactive_notified_at FROM trading.accounts WHERE account_id = ?", OffsetDateTime.class, accountId);
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    @DisplayName("emails the client of a flagged account and records when")
    void emailsClientOfFlaggedAccount() {
        UUID account = flaggedAccount(CLIENT_ID, "ACC-INACTIVE-01", OffsetDateTime.of(2026, 8, 7, 0, 0, 0, 0, ZoneOffset.UTC));

        assertThat(notifier.notifyClients()).isEqualTo(1);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("owner@example.com");
        assertThat(sent.getValue().getFrom()).isEqualTo("no-reply@example.com");
        assertThat(sent.getValue().getSubject()).contains("Brokerage ··E-01").doesNotContain("ACC-INACTIVE-01");
        assertThat(sent.getValue().getText()).contains("Brokerage ··E-01", "Aug 7, 2026", "Deposit funds")
                .doesNotContain("ACC-INACTIVE-01");
        assertThat(notifiedAt(account)).isEqualTo(NOW);
    }

    @Test
    @DisplayName("sends nothing for an account already notified")
    void doesNotResend() {
        flaggedAccount(CLIENT_ID, "ACC-INACTIVE-01", NOW.minusDays(40));
        notifier.notifyClients();

        assertThat(notifier.notifyClients()).isZero();

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("sends one email per flagged account and none for accounts not flagged")
    void oneEmailPerFlaggedAccount() {
        flaggedAccount(CLIENT_ID, "ACC-INACTIVE-01", NOW.minusDays(40));
        flaggedAccount(CLIENT_ID, "ACC-INACTIVE-02", NOW.minusDays(35));

        assertThat(notifier.notifyClients()).isEqualTo(2);

        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("skips clients who are not ACTIVE and leaves their accounts un-notified")
    void skipsClientsNotActive() {
        UUID locked = flaggedAccount(client("locked@example.com", "LOCKED"), "ACC-LOCKED-01", NOW.minusDays(40));
        UUID deleted = flaggedAccount(client("deleted@example.com", "DELETED"), "ACC-DELETED-01", NOW.minusDays(40));

        assertThat(notifier.notifyClients()).isZero();

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(notifiedAt(locked)).isNull();
        assertThat(notifiedAt(deleted)).isNull();
    }

    @Test
    @DisplayName("leaves an account un-notified when its email fails, and sends it on the next run")
    void retriesFailedEmail() {
        UUID account = flaggedAccount(CLIENT_ID, "ACC-INACTIVE-01", NOW.minusDays(40));
        doThrow(new MailSendException("mail server down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThat(notifier.notifyClients()).isZero();
        assertThat(notifiedAt(account)).isNull();

        setUp();
        assertThat(notifier.notifyClients()).isEqualTo(1);
        assertThat(notifiedAt(account)).isEqualTo(NOW);
    }

    @Test
    @DisplayName("keeps sending to other clients after one email fails")
    void continuesAfterFailure() {
        flaggedAccount(client("broken@example.com", "ACTIVE"), "ACC-BROKEN-01", NOW.minusDays(40));
        UUID fine = flaggedAccount(CLIENT_ID, "ACC-INACTIVE-01", NOW.minusDays(40));
        doThrow(new MailSendException("rejected")).when(mailSender).send(
                argThat((SimpleMailMessage m) -> "broken@example.com".equals(m.getTo()[0])));

        assertThat(notifier.notifyClients()).isEqualTo(1);
        assertThat(notifiedAt(fine)).isEqualTo(NOW);
    }

    @Test
    @DisplayName("masks the account number to its last four characters, as the dashboard does")
    void masksAccountNumber() {
        assertThat(InactiveAccountNotifier.maskedName("ACC-008-02")).isEqualTo("Brokerage ··8-02");
        assertThat(InactiveAccountNotifier.maskedName("AB")).isEqualTo("Brokerage ··AB");
    }
}
