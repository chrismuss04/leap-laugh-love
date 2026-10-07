package com.leap.leaplaughlove.iam.auth;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Runs the real service bean, so its transaction commits, to check the email follows the commit
 * and carries a link that works.
 */
@SpringBootTest
@Sql(scripts = "/db/client_registration_test_setup.sql")
@DisplayName("Password reset email wiring")
class PasswordResetEmailWiringTest {

    private static final String CLIENT_ID = "33333333-3333-3333-3333-333333333333";
    private static final String EMAIL = "carol@example.com";
    private static final String LINK_PREFIX = "http://localhost:4200/reset-password?token=";

    @Autowired private PasswordResetService service;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private JavaMailSender mailSender;

    @Test
    @DisplayName("emails the client a usable reset link once the request commits")
    void emailsAUsableLink() {
        jdbc.update("INSERT INTO iam.clients (client_id, email, status) VALUES (?::uuid, ?, 'ACTIVE')",
                CLIENT_ID, EMAIL);

        service.requestReset(EMAIL);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly(EMAIL);
        String text = sent.getValue().getText();
        assertThat(text).contains(LINK_PREFIX);
        String token = text.substring(text.indexOf(LINK_PREFIX) + LINK_PREFIX.length()).split("\\s", 2)[0];
        service.validateToken(token);
    }

    @Test
    @DisplayName("sends nothing for an email that has no client")
    void sendsNothingForAnUnknownEmail() {
        service.requestReset("nobody@example.com");

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }
}
