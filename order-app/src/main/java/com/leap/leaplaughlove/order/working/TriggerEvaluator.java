package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Decides, from the live price, whether an order's stop has been reached and whether it can fill
 * now. Pure functions with no state, shared by order placement and {@link OrderTriggerEngine}.
 *
 * <p>Prices are the side's executable price: a buy fills at the ask, a sell at the bid.
 * <ul>
 *     <li>LIMIT: a buy fills when the ask is at or below the limit, a sell when the bid is at or
 *     above it - always at the quote, which is then the limit or better.</li>
 *     <li>STOP: a buy triggers when the ask rises to the stop, a sell when the bid falls to it,
 *     and then fills at the quote like a market order.</li>
 *     <li>STOP_LIMIT: triggers like a stop, then works as a limit order; it stays triggered even
 *     if the price moves back.</li>
 * </ul>
 */
public final class TriggerEvaluator {

    /**
     * What the price means for an order.
     * @param stopReached whether a stop order's stop price has now been reached (always false
     *                    for types without a stop)
     * @param fillPrice the price to fill at now, or null if the order must keep waiting
     */
    public record Evaluation(boolean stopReached, BigDecimal fillPrice) {
        /**
         * Returns whether the order can fill now.
         * @return true when there is a fill price
         */
        public boolean fills() {
            return fillPrice != null;
        }
    }

    private TriggerEvaluator() {
    }

    /**
     * The price an order on this side would fill at: the ask for a buy, the bid for a sell,
     * falling back to the last trade when that side of the quote is missing.
     * @param side the order's side
     * @param quote the live quote
     * @return the price to 4 decimal places, or null if the quote has no usable price
     */
    public static BigDecimal executablePrice(Order.Side side, QuoteSnapshot quote) {
        if (quote == null) {
            return null;
        }
        BigDecimal sidePrice = side == Order.Side.BUY ? quote.askPrice() : quote.bidPrice();
        BigDecimal price = isPositive(sidePrice) ? sidePrice : quote.lastPrice();
        return isPositive(price) ? price.setScale(4, RoundingMode.HALF_UP) : null;
    }

    /**
     * Evaluates a stored order against the price it would fill at.
     * @param order the order
     * @param price its side's executable price, see {@link #executablePrice}
     * @return whether its stop was reached and whether it fills
     */
    public static Evaluation evaluate(Order order, BigDecimal price) {
        return evaluate(order.getOrderType(), order.getSide(), order.getLimitPrice(), order.getStopPrice(),
                order.getTriggeredAt() != null, price);
    }

    /**
     * Evaluates an order's conditions against the price it would fill at.
     * @param type the order type
     * @param side the order's side
     * @param limitPrice the limit price, for LIMIT and STOP_LIMIT
     * @param stopPrice the stop price, for STOP and STOP_LIMIT
     * @param alreadyTriggered whether the stop was reached earlier
     * @param price the side's executable price; null means there is no price to act on
     * @return whether its stop was reached and whether it fills
     */
    public static Evaluation evaluate(Order.OrderType type, Order.Side side, BigDecimal limitPrice,
                                      BigDecimal stopPrice, boolean alreadyTriggered, BigDecimal price) {
        if (price == null) {
            return new Evaluation(false, null);
        }
        boolean stopReached = type.hasStop() && (alreadyTriggered || stopReached(side, stopPrice, price));
        if (type.hasStop() && !stopReached) {
            return new Evaluation(false, null);
        }
        boolean marketable = !type.hasLimit() || limitReached(side, limitPrice, price);
        return new Evaluation(stopReached, marketable ? price : null);
    }

    /**
     * Whether the price has reached a stop: risen to it for a buy, fallen to it for a sell.
     * @param side the order's side
     * @param stopPrice the stop price
     * @param price the side's executable price
     * @return true once the stop is reached
     */
    public static boolean stopReached(Order.Side side, BigDecimal stopPrice, BigDecimal price) {
        return side == Order.Side.BUY ? price.compareTo(stopPrice) >= 0 : price.compareTo(stopPrice) <= 0;
    }

    /**
     * Whether the price is at the limit or better: at or below it for a buy, at or above for a sell.
     * @param side the order's side
     * @param limitPrice the limit price
     * @param price the side's executable price
     * @return true when the order could fill at this price
     */
    public static boolean limitReached(Order.Side side, BigDecimal limitPrice, BigDecimal price) {
        return side == Order.Side.BUY ? price.compareTo(limitPrice) <= 0 : price.compareTo(limitPrice) >= 0;
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
