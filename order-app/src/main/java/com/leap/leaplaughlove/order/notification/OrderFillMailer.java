package com.leap.leaplaughlove.order.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Emails a client how an order that waited ended: filled, refused when its price was reached, or
 * expired. A cancelled order gets nothing, since the client did it, and an order that executed the
 * moment it was placed never waited, so it never owes an email.
 *
 * <p>A sweep finds every order flagged {@code notice_pending} (set when it went OPEN) that has
 * since reached one of those outcomes. Working from the database rather than from events means it
 * also covers fills that PendingFillRecovery finishes later, needs no Kafka, and retries after a
 * mail outage. The client's address and {@code notify_order_fills} choice are read straight from
 * the shared {@code iam.clients} and {@code iam.client_profile} tables. An order is marked notified, in a transaction of its own,
 * only after the mail server accepts the email, so it is never mailed twice apart from a crash in
 * between. A client who opted out, or is not ACTIVE, just has the flag cleared. Nothing here
 * throws: the order is already decided, so a mail failure is logged and retried next sweep.
 */
@Component
@ConditionalOnProperty(prefix = "order.notifications.email", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class OrderFillMailer {

    private static final Logger log = LoggerFactory.getLogger(OrderFillMailer.class);
    private static final String ACTIVE = "ACTIVE";
    private static final int BATCH_SIZE = 100;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm 'UTC'", Locale.US);

    private static final String PENDING_SQL = """
            SELECT o.order_id, o.side, o.order_type, o.quantity, o.status, o.rejection_reason,
                   o.filled_at, o.rejected_at, o.expired_at,
                   i.symbol, a.account_number, c.email, c.status AS client_status,
                   COALESCE(p.notify_order_fills, TRUE) AS notify_order_fills,
                   (SELECT e.fill_price FROM trading.executions e
                     WHERE e.order_id = o.order_id AND e.status = 'FILLED'
                     ORDER BY e.executed_at DESC LIMIT 1) AS fill_price
              FROM trading.orders o
              JOIN trading.instruments i ON i.instrument_id = o.instrument_id
              JOIN trading.accounts a ON a.account_id = o.account_id
              JOIN iam.clients c ON c.client_id = a.client_id
              LEFT JOIN iam.client_profile p ON p.client_id = c.client_id
             WHERE o.notice_pending AND o.status IN ('FILLED', 'REJECTED', 'EXPIRED')
             ORDER BY o.submitted_at
             LIMIT\s""" + BATCH_SIZE;

    private final JdbcTemplate jdbc;
    private final JavaMailSender mailSender;
    private final TransactionTemplate newTransaction;
    private final String from;
    private final String historyUrl;
    private final Clock clock;

    /** Creates the mailer on the system clock. */
    @Autowired
    public OrderFillMailer(JdbcTemplate jdbc,
                           JavaMailSender mailSender,
                           PlatformTransactionManager transactionManager,
                           @Value("${order.notifications.email.from:no-reply@example.com}") String from,
                           @Value("${order.notifications.email.history-url:http://localhost:4200/orders}")
                           String historyUrl) {
        this(jdbc, mailSender, transactionManager, from, historyUrl, Clock.systemUTC());
    }

    OrderFillMailer(JdbcTemplate jdbc, JavaMailSender mailSender, PlatformTransactionManager transactionManager,
                    String from, String historyUrl, Clock clock) {
        this.jdbc = jdbc;
        this.mailSender = mailSender;
        // Each mark commits on its own, so a crash part-way through re-sends nothing already sent.
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.from = from;
        this.historyUrl = historyUrl;
        this.clock = clock;
    }

    /** Scheduled entry point: emails every outcome still owed. */
    @Scheduled(initialDelayString = "${order.notifications.email.initial-delay-ms:20000}",
               fixedDelayString = "${order.notifications.email.interval-ms:15000}")
    public void run() {
        try {
            notifyClients();
        } catch (RuntimeException ex) {
            log.warn("Sweeping for order outcome emails failed: {}", ex.getMessage());
        }
    }

    /**
     * Emails the client of every finished order that still owes one, and marks each one done.
     * @return the number of emails sent
     */
    public int notifyClients() {
        List<Notice> pending = jdbc.query(PENDING_SQL, OrderFillMailer::notice);
        int sent = 0;
        for (Notice notice : pending) {
            if (!notice.notifyOrderFills() || !ACTIVE.equals(notice.clientStatus())) {
                markDone(notice.orderId(), null);
                continue;
            }
            try {
                mailSender.send(message(notice));
                markDone(notice.orderId(), OffsetDateTime.now(clock));
                sent++;
            } catch (RuntimeException e) {
                log.warn("Could not email about order {}; retrying next sweep: {}", notice.orderId(), e.getMessage());
            }
        }
        if (!pending.isEmpty()) {
            log.info("Order outcome emails sent for {} of {} pending order(s)", sent, pending.size());
        }
        return sent;
    }

    private void markDone(UUID orderId, OffsetDateTime notifiedAt) {
        newTransaction.executeWithoutResult(status -> jdbc.update(
                "UPDATE trading.orders SET notice_pending = FALSE, notified_at = ? WHERE order_id = ? AND notice_pending",
                notifiedAt, orderId));
    }

    private static Notice notice(ResultSet rs, int row) throws SQLException {
        return new Notice(rs.getObject("order_id", UUID.class), rs.getString("side"), rs.getString("order_type"),
                rs.getLong("quantity"), rs.getString("status"), rs.getString("rejection_reason"),
                rs.getObject("filled_at", OffsetDateTime.class), rs.getObject("rejected_at", OffsetDateTime.class),
                rs.getObject("expired_at", OffsetDateTime.class), rs.getString("symbol"),
                rs.getString("account_number"), rs.getString("email"), rs.getString("client_status"),
                rs.getBoolean("notify_order_fills"), rs.getBigDecimal("fill_price"));
    }

    private SimpleMailMessage message(Notice n) {
        String what = typeLabel(n.orderType()) + " " + n.side().toLowerCase(Locale.ROOT) + " of " + n.quantity()
                + " " + n.symbol();
        String subject;
        String lead;
        OffsetDateTime when;
        StringBuilder details = new StringBuilder();
        details.append("Order:    ").append(capitalize(what)).append('\n');
        switch (n.status()) {
            case "FILLED" -> {
                String price = money(n.fillPrice());
                subject = "Your " + what + " filled at " + price;
                lead = "Your " + what + " filled at " + price + ".";
                when = n.filledAt();
                details.append("Price:    ").append(price).append(" per share\n");
                if (n.fillPrice() != null) {
                    details.append("Total:    ").append(money(n.fillPrice().multiply(BigDecimal.valueOf(n.quantity()))))
                            .append('\n');
                }
            }
            case "REJECTED" -> {
                subject = "Your " + what + " was refused";
                lead = "The market reached your price, but your " + what + " was refused"
                        + (n.rejectionReason() != null ? ": " + n.rejectionReason() : ".");
                when = n.rejectedAt();
            }
            default -> {
                subject = "Your " + what + " expired";
                lead = "Your " + what + " expired before the market reached its price, so it did not execute.";
                when = n.expiredAt();
            }
        }
        if (when != null) {
            details.append("When:     ").append(when.atZoneSameInstant(ZoneOffset.UTC).format(TIME)).append('\n');
        }
        details.append("Account:  ").append(maskedName(n.accountNumber())).append('\n');

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(n.email());
        message.setSubject(subject);
        message.setText("Hello,\n\n" + lead + "\n\n" + details + "\n"
                + "See your order history: " + historyUrl + "\n\n"
                + "You can turn these emails off in Settings.\n\n"
                + "Leapfolio\n");
        return message;
    }

    private static String typeLabel(String orderType) {
        return switch (orderType) {
            case "STOP_LIMIT" -> "stop-limit";
            case "TRAILING_STOP" -> "trailing stop";
            default -> orderType.toLowerCase(Locale.ROOT);
        };
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "market price" : "$" + String.format(Locale.US, "%,.2f",
                amount.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Names the account the way the dashboard does, showing only its last four characters, so the
     * full account number never goes out by email.
     */
    static String maskedName(String accountNumber) {
        return "Brokerage ··" + accountNumber.substring(Math.max(0, accountNumber.length() - 4));
    }

    private record Notice(UUID orderId, String side, String orderType, long quantity, String status,
                          String rejectionReason, OffsetDateTime filledAt, OffsetDateTime rejectedAt,
                          OffsetDateTime expiredAt, String symbol, String accountNumber, String email,
                          String clientStatus, boolean notifyOrderFills, BigDecimal fillPrice) {
    }
}
