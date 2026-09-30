package com.leap.leaplaughlove.iam.account;

import com.leap.leaplaughlove.common.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Opens and funds a newly registered client's first account once the registration has committed.
 * account-app reads the client's status from the database, so it cannot see the client before then.
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

    /**
     * Constructs an AccountProvisioningListener.
     * @param accountClient the client used to reach account-app
     * @param jwtService issues the token that authenticates the calls as the new client
     */
    public AccountProvisioningListener(AccountClient accountClient, JwtService jwtService) {
        this.accountClient = accountClient;
        this.jwtService = jwtService;
    }

    /**
     * Creates the client's first account and deposits their initial deposit amount into it.
     * @param event the registration that just committed
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onClientRegistered(ClientRegisteredEvent event) {
        UUID clientId = event.clientId();

        String token;
        UUID accountId;
        try {
            token = jwtService.generateToken(clientId, event.email());
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
}
