package com.leap.leaplaughlove.reporting.recap;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One of a client's filled orders, as reporting.orders holds it. Recaps only ever see fills:
 * rejected, cancelled and expired orders never moved shares or cash.
 * @param accountId the account the order was placed in
 * @param symbol the instrument's trading symbol
 * @param side whether the order bought or sold
 * @param quantity the whole number of units filled
 * @param price the fill price per unit
 * @param filledAt when the order filled
 */
public record RecapFill(UUID accountId, String symbol, Side side, long quantity, BigDecimal price,
                        OffsetDateTime filledAt) {

    /** The direction of a fill. */
    public enum Side {
        /** Bought units. */
        BUY,
        /** Sold units. */
        SELL
    }

    /**
     * The fill's value.
     * @return quantity times price
     */
    public BigDecimal value() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }

    /**
     * The change the fill makes to the units held.
     * @return the quantity, negative for a sell
     */
    public long signedQuantity() {
        return side == Side.BUY ? quantity : -quantity;
    }
}
