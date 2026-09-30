package com.leap.leaplaughlove.iam.account;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.iam.session.ClientSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Opens and funds a newly registered client's first account once the registration has committed.
 * account-app reads the client's status from the database, so it cannot see the client before then.
 *
 * The calls are authenticated with a short-lived login session created for this purpose, because
 * account-app rejects tokens that are not backed by an active session. The session is revoked afterwards.
 *
 * Failures are logged, never thrown: the registration is already committed and must still succeed.
 * Nothing is retried, because a timed-out call may still have created the account or booked the deposit.
 */
@Component
public class AccountProvisioningListener {

    static final String BASE_CURRENCY = "USD";
    static final String DEPOSIT_DESCRIPTION = "Initial deposit";

    private static final Logger log = LoggerFactory.getLogger(AccountProvisioningListener.class);

    private final AccountClient accountClient;
    private final JwtService jwtService;
    private final ClientSessionRepository sessionRepository;
    private final TransactionTemplate newTransaction;

    /**
     * Constructs an AccountProvisioningListener.
     * @param accountClient the client used to reach account-app
     * @param jwtService issues the token that authenticates the calls as the new client
     * @param sessionRepository stores the session that backs that token
     * @param transactionManager runs each session write in its own transaction, since the registration's has ended
     */
    public AccountProvisioningListener(AccountClient accountClient, JwtService jwtService,
                                       ClientSessionRepository sessionRepository,
                                       PlatformTransactionManager transactionManager) {
        this.accountClient = accountClient;
        this.jwtService = jwtService;
        this.sessionRepository = sessionRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Creates the client's first account and deposits their initial deposit amount into it.
     * @param event the registration that just committed
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onClientRegistered(ClientRegisteredEvent event) {
        UUID clientId = event.clientId();
        UUID sessionId = UUID.randomUUID();

        String token;
        try {
            // Committed before any call is made: account-app validates the session on its own connection.
            Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Instant expiresAt = issuedAt.plusSeconds(jwtService.getExpirationSeconds());
            newTransaction.executeWithoutResult(status ->
                    sessionRepository.create(sessionId, clientId, issuedAt, expiresAt));
            token = jwtService.generateToken(clientId, event.email(), sessionId, issuedAt, expiresAt);
        } catch (RuntimeException e) {
            log.error("Registered client {} but could not start a session to open their first account; "
                    + "no account or initial deposit was made", clientId, e);
            return;
        }

        try {
            provision(event, token);
        } finally {
            revokeSession(clientId, sessionId);
        }
    }

    private void provision(ClientRegisteredEvent event, String token) {
        UUID clientId = event.clientId();

        UUID accountId;
        try {
            accountId = accountClient.createAccount(token, BASE_CURRENCY);
        } catch (RuntimeException e) {
            log.error("Registered client {} but could not open their first account; no initial deposit was made",
                    clientId, e);
            return;
        }

        try {
            accountClient.deposit(token, accountId, event.initialDepositAmount(), DEPOSIT_DESCRIPTION);
        } catch (RuntimeException e) {
            log.error("Opened account {} for client {} but the initial deposit of {} is unconfirmed; "
                            + "check the ledger before depositing again",
                    accountId, clientId, event.initialDepositAmount(), e);
        }
    }

    private void revokeSession(UUID clientId, UUID sessionId) {
        try {
            newTransaction.executeWithoutResult(status ->
                    sessionRepository.revoke(sessionId, clientId, Instant.now()));
        } catch (RuntimeException e) {
            log.warn("Could not revoke provisioning session {} for client {}; it will expire on its own",
                    sessionId, clientId, e);
        }
    }
}
