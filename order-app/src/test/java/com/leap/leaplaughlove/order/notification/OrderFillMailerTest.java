package com.leap.leaplaughlove.order.notification;

import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.jdbc.Sql;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Runs the real mailer bean against the database, not @Transactional, so each mark commits as it
 * does in production.
 */
@SpringBootTest(properties = "order.notifications.email.enabled=true")
@Sql(scripts = "/db/order_submission_test_setup.sql")
@DisplayName("Order fill emails")
class OrderFillMailerTest {

    private static final UUID ACCOUNT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AAPL = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001");
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired private OrderFillMailer mailer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderRepository orderRepository;
    @MockBean private JavaMailSender mailSender;

    private UUID order(String status, boolean pending, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, "
                        + "order_type, limit_price, filled_at, rejected_at, expired_at, rejection_reason, notice_pending) "
                        + "VALUES (?, ?, ?, 'BUY', 2, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, ACCOUNT, AAPL, status, type, "LIMIT".equals(type) ? 145 : null,
                "FILLED".equals(status) ? OffsetDateTime.now() : null,
                "REJECTED".equals(status) ? OffsetDateTime.now() : null,
                "EXPIRED".equals(status) ? OffsetDateTime.now() : null,
                "REJECTED".equals(status) ? "Insufficient buying power" : null, pending);
        if ("FILLED".equals(status)) {
            jdbc.update("INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status) "
                    + "VALUES (?, ?, 2, 144.9, 'FILLED')", UUID.randomUUID(), id);
        }
        return id;
    }

    private boolean pending(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT notice_pending FROM trading.orders WHERE order_id = ?", Boolean.class, id));
    }

    private OffsetDateTime notifiedAt(UUID id) {
        return jdbc.queryForObject("SELECT notified_at FROM trading.orders WHERE order_id = ?",
                OffsetDateTime.class, id);
    }

    private SimpleMailMessage onlyMail() {
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        return sent.getValue();
    }

    @Test
    @DisplayName("emails a fill once, with the price and total, and never again")
    void emailsFillOnce() {
        UUID id = order("FILLED", true, "LIMIT");

        assertThat(mailer.notifyClients()).isEqualTo(1);
        assertThat(mailer.notifyClients()).isZero();

        SimpleMailMessage mail = onlyMail();
        assertThat(mail.getTo()).containsExactly("owner@example.com");
        assertThat(mail.getSubject()).isEqualTo("Your limit buy of 2 AAPL filled at $144.90");
        assertThat(mail.getText()).contains("$289.80").contains("Brokerage ··-USD")
                .contains("/orders").contains("Settings").doesNotContain("ACC-OWNER");
        assertThat(pending(id)).isFalse();
        assertThat(notifiedAt(id)).isNotNull();
    }

    @Test
    @DisplayName("explains a refusal and an expiry")
    void refusedAndExpired() {
        order("REJECTED", true, "STOP");
        order("EXPIRED", true, "LIMIT");

        assertThat(mailer.notifyClients()).isEqualTo(2);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(SimpleMailMessage::getSubject)
                .containsExactlyInAnyOrder("Your stop buy of 2 AAPL was refused", "Your limit buy of 2 AAPL expired");
        assertThat(sent.getAllValues()).anySatisfy(m -> assertThat(m.getText()).contains("Insufficient buying power"));
    }

    @Test
    @DisplayName("sends nothing for an order that executed the moment it was placed")
    void immediateFillNotMailed() {
        order("FILLED", false, "LIMIT");

        assertThat(mailer.notifyClients()).isZero();
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("sends nothing for a cancelled order, and cancelling clears the flag")
    void cancelledNotMailed() {
        UUID id = order("OPEN", false, "LIMIT");
        jdbc.update("UPDATE trading.orders SET notice_pending = TRUE WHERE order_id = ?", id);
        Order open = orderRepository.findById(id).orElseThrow();
        open.markCancelled(OffsetDateTime.now());
        orderRepository.saveAndFlush(open);

        assertThat(mailer.notifyClients()).isZero();
        verifyNoInteractions(mailSender);
        assertThat(pending(id)).isFalse();
    }

    @Test
    @DisplayName("a client who opted out gets no email, and the flag is just cleared")
    void optOut() {
        jdbc.update("UPDATE iam.client_profile SET notify_order_fills = FALSE WHERE client_id = ?", OWNER);
        UUID id = order("FILLED", true, "LIMIT");

        assertThat(mailer.notifyClients()).isZero();

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(pending(id)).isFalse();
        assertThat(notifiedAt(id)).isNull();
    }

    @Test
    @DisplayName("retries on the next sweep after a mail failure, then sends once")
    void retriesAfterMailFailure() {
        UUID id = order("FILLED", true, "LIMIT");
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThat(mailer.notifyClients()).isZero();
        assertThat(pending(id)).isTrue();
        assertThat(notifiedAt(id)).isNull();

        doNothing().when(mailSender).send(any(SimpleMailMessage.class));
        assertThat(mailer.notifyClients()).isEqualTo(1);
        assertThat(mailer.notifyClients()).isZero();
        assertThat(pending(id)).isFalse();
    }

    @Test
    @DisplayName("an order going OPEN is flagged, so its outcome is emailed")
    void goingOpenFlagsOrder() {
        UUID id = order("SUBMITTED", false, "LIMIT");
        Order submitted = orderRepository.findById(id).orElseThrow();
        submitted.markOpen(null);
        orderRepository.saveAndFlush(submitted);

        assertThat(pending(id)).isTrue();
    }
}
