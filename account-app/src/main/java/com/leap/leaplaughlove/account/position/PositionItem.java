package com.leap.leaplaughlove.account.position;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Represents a trading position item within an account, including details about the instrument and the position quantity and cost.
 * @param instrumentId the unique identifier of the instrument
 * @param symbol the trading symbol of the instrument
 * @param instrumentName the name of the instrument
 * @param assetClass the asset class of the instrument
 * @param quantity the quantity of the position
 * @param avgCost the average cost of the position
 */
public record PositionItem(
        UUID instrumentId,
        String symbol,
        String instrumentName,
        String assetClass,
        long quantity,
        BigDecimal avgCost
) {}

