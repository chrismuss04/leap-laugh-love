package com.leap.leaplaughlove.iam.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PasswordResetLinkListenerTest {

    private static final String LINK = "http://localhost:4200/reset-password?token=abc";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final PasswordResetLinkListener listener =
            new PasswordResetLinkListener(mailSender, "no-reply@leapfolio.test");

    private final PasswordResetRequestedEvent event = new PasswordResetRequestedEvent(
            "alice@example.com", LINK, Instant.parse("2026-10-07T15:45:00Z"));

    @Test
    void emailsTheResetLinkToTheClient() {
        listener.onPasswordResetRequested(event);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("alice@example.com");
        assertThat(sent.getValue().getFrom()).isEqualTo("no-reply@leapfolio.test");
        assertThat(sent.getValue().getSubject()).isEqualTo("Reset your Leapfolio password");
        assertThat(sent.getValue().getText())
                .contains(LINK)
                .contains("expires at 3:45 PM UTC on Oct 7, 2026");
    }

    // The request already answered 204 either way, so a mail server being down must not surface.
    @Test
    void swallowsAMailFailure() {
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertDoesNotThrow(() -> listener.onPasswordResetRequested(event));
    }

    // Verify the link is only delivered once its token is committed and usable.
    @Test
    void deliversAfterCommit() throws NoSuchMethodException {
        var annotation = PasswordResetLinkListener.class
                .getMethod("onPasswordResetRequested", PasswordResetRequestedEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    }
}
