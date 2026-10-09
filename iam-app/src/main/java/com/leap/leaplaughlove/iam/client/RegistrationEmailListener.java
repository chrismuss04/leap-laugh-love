package com.leap.leaplaughlove.iam.client;

import com.leap.leaplaughlove.iam.client.RegistrationEmailEvent.Detail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sends the emails a registration request leads to, once the request has committed, so a
 * confirmation link never arrives before it works.
 *
 * The email is handed to another thread: how many go out depends on whether the applicant's
 * details were already registered, and the request must not take longer to answer because of it.
 *
 * Nothing here throws: the applicant was told to check their email either way, so a mail failure
 * is logged and they simply apply again. The confirmation link is a credential and is never logged.
 */
@Component
public class RegistrationEmailListener {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEmailListener.class);
    private static final DateTimeFormatter EXPIRY =
            DateTimeFormatter.ofPattern("h:mm a 'UTC on' MMM d, yyyy", Locale.US);
    private static final String APPLICATION_SUBJECT = "Your Leapfolio account application";
    private static final String SIGN_IN_HINT = "sign in as usual, or use \"Forgot password\" on the sign-in "
            + "page if you can't remember your password";

    private final JavaMailSender mailSender;
    private final TaskExecutor executor;
    private final String from;

    /**
     * Constructs a new RegistrationEmailListener with the specified dependencies.
     * @param mailSender sends the email over the configured SMTP server
     * @param executor runs each send off the request's thread
     * @param from the address the email is sent from
     */
    public RegistrationEmailListener(JavaMailSender mailSender,
                                     @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                                     @Value("${app.registration.email.from:no-reply@example.com}") String from) {
        this.mailSender = mailSender;
        this.executor = executor;
        this.from = from;
    }

    /**
     * Emails the applicant, or the client whose details an application reused.
     * @param event the email a registration request that just committed calls for
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRegistrationEmail(RegistrationEmailEvent event) {
        try {
            executor.execute(() -> send(event));
        } catch (RuntimeException e) {
            log.warn("Could not queue a registration email ({}): {}", event.kind(), e.getMessage());
        }
    }

    private void send(RegistrationEmailEvent event) {
        try {
            mailSender.send(message(event));
        } catch (RuntimeException e) {
            log.warn("Could not send a registration email ({}): {}", event.kind(), e.getMessage());
        }
    }

    private SimpleMailMessage message(RegistrationEmailEvent event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(event.to());
        message.setSubject(switch (event.kind()) {
            case VERIFY -> "Confirm your email to open your Leapfolio account";
            case ALREADY_REGISTERED, NOT_COMPLETED -> APPLICATION_SUBJECT;
            case DETAILS_REUSED -> "Someone applied for a Leapfolio account with your details";
        });
        message.setText("Hello,\n\n" + body(event) + "\n\nLeapfolio\n");
        return message;
    }

    // Names which details were used, never their values: an email is no place for an SSN.
    private static String reused(Set<Detail> details) {
        List<String> names = Arrays.stream(Detail.values()).filter(details::contains).map(detail -> switch (detail) {
            case EMAIL -> "the email address";
            case PHONE -> "the phone number";
            case SSN -> "the Social Security Number";
        }).toList();
        if (names.isEmpty()) {
            return "personal details that match the ones";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private static String body(RegistrationEmailEvent event) {
        return switch (event.kind()) {
            case VERIFY -> "Thanks for applying for a Leapfolio account. Open this link to confirm your "
                    + "email address and open your account:\n\n"
                    + event.link() + "\n\n"
                    + "The link works once and expires at "
                    + event.expiresAt().atZone(ZoneOffset.UTC).format(EXPIRY) + ". "
                    + "Your account is not open until you use it.\n\n"
                    + "If you did not apply for a Leapfolio account, you can ignore this email.";
            case ALREADY_REGISTERED -> "Someone just applied for a Leapfolio account with this email address, "
                    + "but it is already registered, so no new account was opened.\n\n"
                    + "If that was you, " + SIGN_IN_HINT + ".\n\n"
                    + "If it wasn't you, there is nothing you need to do: your account has not been changed.";
            case NOT_COMPLETED -> "We could not open a Leapfolio account with the details you submitted, "
                    + "so no account was created.\n\n"
                    + "If you already have a Leapfolio account, " + SIGN_IN_HINT + ", using the email address "
                    + "you registered with. Otherwise, please contact Leapfolio for help.";
            case DETAILS_REUSED -> "Someone just applied for a new Leapfolio account using "
                    + reused(event.details()) + " on your account. No new account was opened, and your "
                    + "account has not been changed.\n\n"
                    + "If that was you, you already have an account under this email address: "
                    + SIGN_IN_HINT + ".\n\n"
                    + "If it wasn't you, please report it to Leapfolio immediately.";
        };
    }
}
