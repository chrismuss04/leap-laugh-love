package com.leap.leaplaughlove.iam.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Emails a password reset link once its token has committed, so the link never arrives before
 * it works.
 *
 * Nothing here throws: the caller is told the same thing whether or not an email went out, so a
 * mail failure is logged and the client simply requests another link. The reset link is a
 * credential and is never logged.
 */
@Component
public class PasswordResetLinkListener {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetLinkListener.class);
    private static final DateTimeFormatter EXPIRY =
            DateTimeFormatter.ofPattern("h:mm a 'UTC on' MMM d, yyyy", Locale.US);

    private final JavaMailSender mailSender;
    private final String from;

    /**
     * Constructs a new PasswordResetLinkListener with the specified dependencies.
     * @param mailSender sends the email over the configured SMTP server
     * @param from the address the email is sent from
     */
    public PasswordResetLinkListener(JavaMailSender mailSender,
                                     @Value("${app.password-reset.email.from:no-reply@example.com}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /**
     * Emails the reset link to the client.
     * @param event the reset request that just committed
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
        try {
            mailSender.send(message(event));
        } catch (RuntimeException e) {
            log.warn("Could not send a password reset email: {}", e.getMessage());
        }
    }

    private SimpleMailMessage message(PasswordResetRequestedEvent event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(event.email());
        message.setSubject("Reset your Leapfolio password");
        message.setText("Hello,\n\n"
                + "We received a request to reset the password for your Leapfolio account. "
                + "Open this link to choose a new one:\n\n"
                + event.resetLink() + "\n\n"
                + "The link works once and expires at "
                + event.expiresAt().atZone(ZoneOffset.UTC).format(EXPIRY) + ".\n\n"
                + "If you did not request a password reset, please report it to Leapfolio immediately.\n\n"
                + "Leapfolio\n");
        return message;
    }
}
