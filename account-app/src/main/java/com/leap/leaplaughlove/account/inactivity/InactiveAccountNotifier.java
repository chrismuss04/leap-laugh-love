package com.leap.leaplaughlove.account.inactivity;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountRepository;
import com.leap.leaplaughlove.account.client.ClientStatus;
import com.leap.leaplaughlove.account.client.ClientStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Emails a client about each of their accounts the nightly check has flagged inactive, once that
 * check has committed, so an email never goes out for a flag that rolled back.
 *
 * Works through every flagged account not yet notified, not only those flagged this run, so a send
 * that failed is retried the next night. An account is marked notified only after the mail server
 * accepts its email, each mark committing on its own, so a crash part-way through re-sends nothing
 * already sent. Clients who are not ACTIVE are skipped and stay un-notified. Nothing here throws:
 * the flags are already committed, so a mail failure is logged and must not fail the job.
 */
@Component
@ConditionalOnProperty(prefix = "account.inactivity.email", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InactiveAccountNotifier {

    private static final Logger log = LoggerFactory.getLogger(InactiveAccountNotifier.class);
    private static final String ACTIVE = "ACTIVE";
    // Matches the "Inactive since" date on the dashboard.
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private final AccountRepository accountRepository;
    private final ClientStatusRepository clientStatusRepository;
    private final JavaMailSender mailSender;
    private final TransactionTemplate newTransaction;
    private final String from;
    private final Clock clock;

    /** Creates the notifier on the system clock. */
    @Autowired
    public InactiveAccountNotifier(AccountRepository accountRepository,
                                   ClientStatusRepository clientStatusRepository,
                                   JavaMailSender mailSender,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${account.inactivity.email.from:no-reply@example.com}") String from) {
        this(accountRepository, clientStatusRepository, mailSender, transactionManager, from, Clock.systemUTC());
    }

    InactiveAccountNotifier(AccountRepository accountRepository, ClientStatusRepository clientStatusRepository,
                            JavaMailSender mailSender, PlatformTransactionManager transactionManager,
                            String from, Clock clock) {
        this.accountRepository = accountRepository;
        this.clientStatusRepository = clientStatusRepository;
        this.mailSender = mailSender;
        // After commit the check's transaction is finished but still bound, and a write that joined
        // it would never commit, so each mark runs in a transaction of its own.
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.from = from;
        this.clock = clock;
    }

    /**
     * Emails the clients of newly inactive accounts once the nightly check has committed.
     * @param event the check that just committed
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInactivityChecked(InactivityCheckedEvent event) {
        notifyClients();
    }

    /**
     * Emails the client of every flagged account not yet notified, and marks each one sent.
     * @return the number of emails sent
     */
    public int notifyClients() {
        List<Account> pending = accountRepository.findByInactiveSinceIsNotNullAndInactiveNotifiedAtIsNull();
        if (pending.isEmpty()) {
            return 0;
        }
        Map<UUID, ClientStatus> clients = clientStatusRepository
                .findByClientIdIn(pending.stream().map(Account::getClientId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ClientStatus::getClientId, Function.identity()));

        int sent = 0;
        for (Account account : pending) {
            ClientStatus client = clients.get(account.getClientId());
            if (client == null || !ACTIVE.equals(client.getStatus())) {
                continue;
            }
            try {
                mailSender.send(message(client.getEmail(), account));
                OffsetDateTime notifiedAt = OffsetDateTime.now(clock);
                newTransaction.executeWithoutResult(
                        status -> accountRepository.markInactiveNotified(account.getAccountId(), notifiedAt));
                sent++;
            } catch (RuntimeException e) {
                log.warn("Could not email client {} about inactive account {}; retrying next run: {}",
                        client.getClientId(), account.getAccountId(), e.getMessage());
            }
        }
        log.info("Inactive account emails sent for {} of {} un-notified account(s)", sent, pending.size());
        return sent;
    }

    private SimpleMailMessage message(String to, Account account) {
        String accountName = maskedName(account.getAccountNumber());
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Your Leapfolio account " + accountName + " is inactive");
        message.setText("Hello,\n\n"
                + "Your account " + accountName + " has held no cash or shares since "
                + account.getInactiveSince().atZoneSameInstant(ZoneOffset.UTC).format(DATE)
                + ", so it has been marked inactive.\n\n"
                + "Deposit funds to reactivate it.\n\n"
                + "Leapfolio\n");
        return message;
    }

    /**
     * Names the account the way the dashboard does, showing only its last four characters, so the
     * full account number never goes out by email.
     */
    static String maskedName(String accountNumber) {
        return "Brokerage ··" + accountNumber.substring(Math.max(0, accountNumber.length() - 4));
    }
}
