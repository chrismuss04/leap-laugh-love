package com.leap.leaplaughlove.iam.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers a password reset link once its token has committed, so the link never arrives before
 * it works.
 *
 * Email delivery is not wired up yet: until it is, the link is written to the log so the flow can
 * be exercised locally. The reset link is a credential - replace the log line with the email send
 * rather than keeping both.
 */
@Component
public class PasswordResetLinkListener {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetLinkListener.class);

    /**
     * Delivers the reset link to the client.
     * @param event the reset request that just committed
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
        log.info("Password reset link for {} (valid until {}): {}",
                event.email(), event.expiresAt(), event.resetLink());
    }
}
