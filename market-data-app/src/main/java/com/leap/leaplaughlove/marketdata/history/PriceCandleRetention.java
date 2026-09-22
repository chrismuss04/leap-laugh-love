package com.leap.leaplaughlove.marketdata.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Drops candles that have aged out of their width's retention window, so the table reaches a
 * steady size instead of growing for as long as the service runs.
 *
 * <p>The accumulator writes every width forever, but only the finest width is ever read at short
 * range - a month-long chart is served from hourly candles, not from the ~43,000 minute candles
 * covering the same period. Left unpruned the 60s width alone is ~1,440 rows per instrument per
 * day, which is most of the growth and almost none of the value.
 *
 * <p>Retention is configured in the same {@code bucketSeconds:days} form as the backfill's tiers,
 * and should normally match them: the backfill generates that much history on first boot, and
 * this keeps exactly that much thereafter, so the table settles at the size the backfill created
 * rather than drifting away from it. A width the accumulator writes but retention does not list
 * is kept forever.
 */
@Component
@ConditionalOnProperty(prefix = "marketdata.history.retention", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PriceCandleRetention {

    private static final Logger log = LoggerFactory.getLogger(PriceCandleRetention.class);

    private final PriceCandleRepository candleRepository;
    private final List<CandleTier> tiers;

    /**
     * Creates a new PriceCandleRetention.
     * @param candleRepository repository used to delete aged-out candles
     * @param tierSpec how long to keep each bucket width, as {@code bucketSeconds:days} pairs
     */
    public PriceCandleRetention(PriceCandleRepository candleRepository,
                                 @Value("${marketdata.history.retention.tiers:86400:365,3600:90,300:7,60:2}")
                                 List<String> tierSpec) {
        this.candleRepository = candleRepository;
        this.tiers = CandleTier.parse(tierSpec);
    }

    /**
     * Deletes every candle that has aged out of its width's window.
     */
    @Scheduled(initialDelayString = "${marketdata.history.retention.initial-delay-ms:300000}",
            fixedRateString = "${marketdata.history.retention.interval-ms:3600000}")
    @Transactional
    public void prune() {
        OffsetDateTime now = OffsetDateTime.now();
        int total = 0;
        for (CandleTier tier : tiers) {
            int deleted = candleRepository.deleteByBucketSecondsAndBucketStartBefore(
                    tier.bucketSeconds(), now.minusDays(tier.days()));
            if (deleted > 0) {
                log.info("Pruned {} candles of width {}s older than {} days",
                        deleted, tier.bucketSeconds(), tier.days());
            }
            total += deleted;
        }
        if (total > 0) {
            log.info("Candle retention prune removed {} rows", total);
        }
    }
}
