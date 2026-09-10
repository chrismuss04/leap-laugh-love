package com.leap.leaplaughlove.trading.position;

import java.math.BigDecimal;
import java.util.UUID;

public record PositionItem(
        UUID instrumentId,
        String symbol,
        String instrumentName,
        String assetClass,
        long quantity,
        BigDecimal averageCost
) {
}
