package com.leap.leaplaughlove.iam.auth;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PasswordResetLinkListenerTest {

    private final PasswordResetLinkListener listener = new PasswordResetLinkListener();

    @Test
    void handlesAResetRequest() {
        var event = new PasswordResetRequestedEvent("alice@example.com",
                "http://localhost:4200/reset-password?token=abc", Instant.now().plusSeconds(1800));

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
