package com.leap.leaplaughlove.marketdata.history;

import java.util.ArrayList;
import java.util.List;

/**
 * One candle resolution and how many days of it are kept: a bucket width paired with a window.
 *
 * <p>Shared by the backfill, which generates this much history, and the retention prune, which
 * keeps this much. Configuring both from the same {@code bucketSeconds:days} form is what makes
 * the steady state match the shape the backfill produces, instead of the finest width growing
 * without bound behind it.
 *
 * @param bucketSeconds the bucket width, in seconds
 * @param days how many days of that width the window covers
 */
record CandleTier(int bucketSeconds, long days) {

    /**
     * Parses {@code bucketSeconds:days} pairs.
     * @param spec the raw configured pairs
     * @return the parsed tiers, in the order given
     * @throws IllegalArgumentException if the list is empty or any pair is malformed
     */
    static List<CandleTier> parse(List<String> spec) {
        if (spec.isEmpty()) {
            throw new IllegalArgumentException("at least one candle tier must be configured");
        }
        List<CandleTier> parsed = new ArrayList<>();
        for (String pair : spec) {
            String[] parts = pair.trim().split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "candle tier must be bucketSeconds:days, got: " + pair);
            }
            int bucketSeconds;
            long days;
            try {
                bucketSeconds = Integer.parseInt(parts[0].trim());
                days = Long.parseLong(parts[1].trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("candle tier must be numeric, got: " + pair, ex);
            }
            if (bucketSeconds <= 0 || days <= 0) {
                throw new IllegalArgumentException("candle tier values must be positive: " + pair);
            }
            parsed.add(new CandleTier(bucketSeconds, days));
        }
        return List.copyOf(parsed);
    }
}
