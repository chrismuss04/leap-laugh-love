package com.leap.leaplaughlove.account.inactivity;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountRepository;
import com.leap.leaplaughlove.account.account.AccountRepository.EmptyAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Nightly job that flags accounts empty for longer than the threshold and clears accounts that no
 * longer are. Each run then publishes an {@link InactivityCheckedEvent}, delivered after it commits,
 * which {@link InactiveAccountNotifier} uses to email the clients of newly flagged accounts.
 */
@Component
@ConditionalOnProperty(prefix = "account.inactivity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InactiveAccountDetector {

    private static final Logger log = LoggerFactory.getLogger(InactiveAccountDetector.class);

    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final int thresholdDays;
    private final Clock clock;

    /** Creates the detector on the system clock. */
    @Autowired
    public InactiveAccountDetector(AccountRepository accountRepository, ApplicationEventPublisher eventPublisher,
                                   @Value("${account.inactivity.threshold-days:30}") int thresholdDays) {
        this(accountRepository, eventPublisher, thresholdDays, Clock.systemUTC());
    }

    InactiveAccountDetector(AccountRepository accountRepository, ApplicationEventPublisher eventPublisher,
                            int thresholdDays, Clock clock) {
        this.accountRepository = accountRepository;
        this.eventPublisher = eventPublisher;
        this.thresholdDays = thresholdDays;
        this.clock = clock;
    }

    @Scheduled(cron = "${account.inactivity.cron:0 0 2 * * *}", zone = "UTC")
    @Transactional
    public void detect() {
        Map<UUID, OffsetDateTime> empty = accountRepository
                .findEmptySince(OffsetDateTime.now(clock).minusDays(thresholdDays)).stream()
                .collect(Collectors.toMap(EmptyAccount::getAccountId, EmptyAccount::getEmptySince));

        List<Account> cleared = accountRepository.findByInactiveSinceIsNotNull().stream()
                .filter(a -> !empty.containsKey(a.getAccountId())).toList();
        // A reactivated account's next inactive period gets an email of its own.
        cleared.forEach(a -> {
            a.setInactiveSince(null);
            a.setInactiveNotifiedAt(null);
        });
        List<Account> flagged = accountRepository.findAllById(empty.keySet()).stream()
                .filter(a -> a.getInactiveSince() == null).toList();
        flagged.forEach(a -> a.setInactiveSince(empty.get(a.getAccountId())));
        log.info("Inactive account check flagged {} and cleared {} account(s)", flagged.size(), cleared.size());
        eventPublisher.publishEvent(new InactivityCheckedEvent());
    }
}
