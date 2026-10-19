package com.leap.leaplaughlove.reporting.recap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;

/**
 * Keeps last month's recaps built. Two schedules:
 * <ul>
 *   <li>Monthly, just after a UTC month ends: builds the month that ended, so recaps are ready the
 *       morning a new month starts.</li>
 *   <li>Catch-up, shortly after startup and every few minutes after: builds last month if it has no
 *       recaps yet, or rebuilds it if a fill from up to its end was loaded after it was built. It
 *       covers a service that was down at the monthly run, a first deploy, a run that failed because
 *       market data was unreachable or still starting, and a run that went ahead while the ETL was
 *       down or still loading the month's orders. Once the month is built and current it costs one
 *       query.</li>
 * </ul>
 * Off when reporting.recap.enabled is false, as in tests.
 */
@Component
@ConditionalOnProperty(name = "reporting.recap.enabled", havingValue = "true", matchIfMissing = true)
public class RecapJob {

    private static final Logger log = LoggerFactory.getLogger(RecapJob.class);

    private final RecapGenerator generator;
    private final RecapSnapshotRepository snapshots;
    private final Clock clock;

    /**
     * Creates the job.
     * @param generator builds a month's recaps
     * @param snapshots tells whether a month is built
     * @param clock the UTC clock months are measured on
     */
    public RecapJob(RecapGenerator generator, RecapSnapshotRepository snapshots, Clock clock) {
        this.generator = generator;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    /** Builds the month that just ended. 00:30 UTC on the 1st by default. */
    @Scheduled(cron = "${reporting.recap.cron:0 30 0 1 * *}", zone = "UTC")
    public void buildLastMonth() {
        build(lastMonth());
    }

    /** Builds last month if it has no recaps yet, or fills have arrived since it was built. */
    @Scheduled(initialDelayString = "${reporting.recap.catch-up-initial-delay-ms:60000}",
            fixedDelayString = "${reporting.recap.catch-up-delay-ms:300000}")
    public void catchUp() {
        YearMonth month = lastMonth();
        if (!snapshots.isUpToDate(month)) {
            build(month);
        }
    }

    private void build(YearMonth month) {
        try {
            generator.generate(month);
        } catch (RuntimeException e) {
            // Logged rather than thrown: the catch-up run tries again in a few minutes.
            log.warn("Recaps of {} not built: {}", month, e.getMessage(), e);
        }
    }

    private YearMonth lastMonth() {
        return YearMonth.now(clock).minusMonths(1);
    }
}
