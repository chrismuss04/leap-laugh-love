package com.leap.leaplaughlove.account.inactivity;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountRepository;
import com.leap.leaplaughlove.account.account.AccountRepository.EmptyAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Nightly job that flags accounts left empty for longer than the threshold, and clears the flag
 * once they hold cash or shares again. Rerunning it changes nothing.
 */
@Component
@ConditionalOnProperty(prefix = "account.inactivity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InactiveAccountDetector {

    private static final Logger log = LoggerFactory.getLogger(InactiveAccountDetector.class);

    private final AccountRepository accountRepository;
    private final int thresholdDays;
    private final Clock clock;

    /** Creates the detector on the system clock. */
    @Autowired
    public InactiveAccountDetector(AccountRepository accountRepository,
                                   @Value("${account.inactivity.threshold-days:30}") int thresholdDays) {
        this(accountRepository, thresholdDays, Clock.systemUTC());
    }

    InactiveAccountDetector(AccountRepository accountRepository, int thresholdDays, Clock clock) {
        this.accountRepository = accountRepository;
        this.thresholdDays = thresholdDays;
        this.clock = clock;
    }

    /** Flags newly inactive accounts and clears accounts that are no longer empty. */
    @Scheduled(cron = "${account.inactivity.cron:0 0 2 * * *}", zone = "UTC")
    @Transactional
    public void detect() {
        Map<UUID, OffsetDateTime> empty = accountRepository
                .findEmptySince(OffsetDateTime.now(clock).minusDays(thresholdDays)).stream()
                .collect(Collectors.toMap(EmptyAccount::getAccountId, EmptyAccount::getEmptySince));

        int cleared = 0;
        for (Account account : accountRepository.findByInactiveSinceIsNotNull()) {
            if (!empty.containsKey(account.getAccountId())) {
                account.setInactiveSince(null);
                cleared++;
            }
        }
        int flagged = 0;
        for (Account account : accountRepository.findAllById(empty.keySet())) {
            if (account.getInactiveSince() == null) {
                flagged++;
            }
            account.setInactiveSince(empty.get(account.getAccountId()));
        }
        log.info("Inactive account check flagged {} and cleared {} account(s)", flagged, cleared);
    }
}
