package com.leap.leaplaughlove.iam.client;

import com.leap.leaplaughlove.iam.client.RegistrationEmailEvent.Detail;
import com.leap.leaplaughlove.iam.client.RegistrationEmailEvent.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RegistrationEmailListenerTest {

    private static final String LINK = "http://localhost:4200/verify-email?token=abc";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    // Runs each send where it is queued, so the tests see it straight away.
    private final RegistrationEmailListener listener =
            new RegistrationEmailListener(mailSender, Runnable::run, "no-reply@leapfolio.test");

    private SimpleMailMessage sent() {
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        return sent.getValue();
    }

    @Test
    void emailsTheConfirmationLinkToTheApplicant() {
        listener.onRegistrationEmail(RegistrationEmailEvent.verify(
                "ada@example.com", LINK, Instant.parse("2026-10-07T15:45:00Z")));

        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("ada@example.com");
        assertThat(message.getFrom()).isEqualTo("no-reply@leapfolio.test");
        assertThat(message.getSubject()).isEqualTo("Confirm your email to open your Leapfolio account");
        assertThat(message.getText())
                .contains(LINK)
                .contains("expires at 3:45 PM UTC on Oct 7, 2026");
    }

    // The applicant may be probing for someone else's details, so their emails never say which matched.
    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"ALREADY_REGISTERED", "NOT_COMPLETED"})
    void emailsToTheApplicantNeverNameThePhoneNumberOrSsn(Kind kind) {
        listener.onRegistrationEmail(RegistrationEmailEvent.of(kind, "applicant@example.com"));

        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("applicant@example.com");
        assertThat(message.getText())
                .doesNotContain("http")
                .doesNotContainIgnoringCase("ssn")
                .doesNotContainIgnoringCase("social security")
                .doesNotContainIgnoringCase("phone");
    }

    // The client whose detail it was is told which, so they know what to watch or report.
    @Test
    void tellsTheOwnerTheirSsnWasUsed() {
        listener.onRegistrationEmail(RegistrationEmailEvent.detailsReused("owner@example.com", Set.of(Detail.SSN)));

        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("owner@example.com");
        assertThat(message.getText())
                .contains("using the Social Security Number on your account")
                .doesNotContain("phone number");
    }

    @Test
    void tellsTheOwnerTheirPhoneNumberWasUsed() {
        listener.onRegistrationEmail(RegistrationEmailEvent.detailsReused("owner@example.com", Set.of(Detail.PHONE)));

        assertThat(sent().getText())
                .contains("using the phone number on your account")
                .doesNotContain("Social Security");
    }

    @Test
    void tellsTheOwnerBothWereUsed() {
        listener.onRegistrationEmail(RegistrationEmailEvent.detailsReused(
                "owner@example.com", Set.of(Detail.SSN, Detail.PHONE)));

        assertThat(sent().getText())
                .contains("using the phone number and the Social Security Number on your account");
    }

    @Test
    void tellsTheOwnerEverythingOfTheirsThatWasUsed() {
        listener.onRegistrationEmail(RegistrationEmailEvent.detailsReused(
                "owner@example.com", Set.of(Detail.SSN, Detail.EMAIL, Detail.PHONE)));

        assertThat(sent().getText()).contains("using the email address, the phone number and the "
                + "Social Security Number on your account. No new account was opened");
    }

    // The request must not wait on the mail server: how many emails go out depends on the outcome.
    @Test
    void sendsOffTheRequestThread() {
        List<Runnable> queued = new ArrayList<>();
        new RegistrationEmailListener(mailSender, queued::add, "no-reply@leapfolio.test")
                .onRegistrationEmail(RegistrationEmailEvent.of(Kind.NOT_COMPLETED, "ada@example.com"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        queued.forEach(Runnable::run);
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    // The request already answered 202 either way, so a mail server being down must not surface.
    @Test
    void swallowsAMailFailure() {
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertDoesNotThrow(() -> listener.onRegistrationEmail(
                RegistrationEmailEvent.of(Kind.ALREADY_REGISTERED, "ada@example.com")));
    }

    @Test
    void swallowsAFullQueue() {
        TaskExecutor full = task -> {
            throw new TaskRejectedException("queue full");
        };

        assertDoesNotThrow(() -> new RegistrationEmailListener(mailSender, full, "no-reply@leapfolio.test")
                .onRegistrationEmail(RegistrationEmailEvent.of(Kind.ALREADY_REGISTERED, "ada@example.com")));
    }

    // Verify the link is only delivered once its application is committed and usable.
    @Test
    void deliversAfterCommit() throws NoSuchMethodException {
        var annotation = RegistrationEmailListener.class
                .getMethod("onRegistrationEmail", RegistrationEmailEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    }
}
