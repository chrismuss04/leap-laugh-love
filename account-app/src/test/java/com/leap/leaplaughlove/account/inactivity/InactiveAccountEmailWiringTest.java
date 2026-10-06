package com.leap.leaplaughlove.account.inactivity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.jdbc.Sql;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Runs the real detector bean, so its transaction commits, to check the email follows the commit.
 * Not @Transactional for the same reason.
 */
@SpringBootTest(properties = {"account.inactivity.enabled=true", "account.inactivity.email.enabled=true"})
@Sql(scripts = "/db/positions_test_setup.sql")
@DisplayName("Inactive account email wiring")
class InactiveAccountEmailWiringTest {

    private static final UUID CLIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired private InactiveAccountDetector detector;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private JavaMailSender mailSender;

    @Test
    @DisplayName("emails the client once the nightly check that flagged their account commits")
    void emailsAfterCheckCommits() {
        UUID account = UUID.randomUUID();
        jdbc.update("INSERT INTO trading.accounts (account_id, client_id, account_number, status, base_currency, "
                + "trading_enabled, created_at) VALUES (?, ?, 'ACC-NEVER-FUNDED', 'ACTIVE', 'USD', TRUE, ?)",
                account, CLIENT_ID, OffsetDateTime.now().minusDays(45));

        detector.detect();

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("owner@example.com");
        assertThat(jdbc.queryForObject("SELECT inactive_notified_at FROM trading.accounts WHERE account_id = ?",
                OffsetDateTime.class, account)).isNotNull();
    }
}
