package com.leap.leaplaughlove.account.position;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Represents a trading position item within an account, including details about the instrument and the position quantity and cost.
 * Today is the trading day so far (see {@link com.leap.leaplaughlove.account.common.TradingDay}); with
 * the previous close, its two fields give the day's gain or loss even on shares bought or sold today:
 * {@code quantity * price - quantityAtOpen * previousClose + todayCashFlow}.
 * @param instrumentId the unique identifier of the instrument
 * @param symbol the trading symbol of the instrument
 * @param instrumentName the name of the instrument
 * @param assetClass the asset class of the instrument
 * @param quantity the quantity of the position
 * @param averageCost the average cost of the position
 * @param quantityAtOpen the quantity held when the trading day began
 * @param todayCashFlow cash the position returned today: sale proceeds less purchase costs, so
 *     negative after a buy
 */
public record PositionItem(
        UUID instrumentId,
        String symbol,
        String instrumentName,
        String assetClass,
        long quantity,
        BigDecimal averageCost,
        long quantityAtOpen,
        BigDecimal todayCashFlow
) {}

