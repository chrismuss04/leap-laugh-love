package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.order.order.Order;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The arithmetic of a trailing stop, with no state, shared by order placement, editing and
 * {@link OrderTriggerEngine}.
 *
 * <p>A trailing stop remembers the best price it has seen - the highest bid for a sell, the lowest
 * ask for a buy - and keeps its stop a fixed amount, or percent, behind it. The stop only ever
 * moves in the order's favour: a sell's stop rises as the price does and stays put as it falls, so
 * it locks in gains and sells once the price has fallen the trail away from its high.
 */
public final class TrailingStop {

    private TrailingStop() {
    }

    /**
     * The best price seen after a new one: the higher of the two for a sell, the lower for a buy.
     * @param side the order's side
     * @param best the best price seen so far
     * @param price the side's executable price now
     * @return the new best price
     */
    public static BigDecimal best(Order.Side side, BigDecimal best, BigDecimal price) {
        if (best == null) {
            return price;
        }
        boolean better = side == Order.Side.SELL ? price.compareTo(best) > 0 : price.compareTo(best) < 0;
        return better ? price : best;
    }

    /**
     * How far the stop sits from the best price.
     * @param best the best price seen
     * @param amount the trail in dollars, or null
     * @param percent the trail in percent of the best price, or null
     * @return the distance in dollars
     */
    public static BigDecimal distance(BigDecimal best, BigDecimal amount, BigDecimal percent) {
        if (amount != null) {
            return amount;
        }
        return best.multiply(percent).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
    }

    /**
     * The stop price a trail sets: below the best price for a sell, above it for a buy.
     * @param side the order's side
     * @param best the best price seen
     * @param amount the trail in dollars, or null
     * @param percent the trail in percent, or null
     * @return the stop price to four decimal places; zero or less if a sell's trail exceeds the price
     */
    public static BigDecimal stopFor(Order.Side side, BigDecimal best, BigDecimal amount, BigDecimal percent) {
        BigDecimal distance = distance(best, amount, percent);
        BigDecimal stop = side == Order.Side.SELL ? best.subtract(distance) : best.add(distance);
        return stop.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Moves a trailing order's stop for a new price: raises it behind a new high (sell) or lowers it
     * behind a new low (buy), and leaves it alone otherwise.
     * @param order the trailing order, with its best price and trail set
     * @param price the side's executable price now
     * @return the new best price and stop, or null if the stop doesn't move
     */
    public static Move ratchet(Order order, BigDecimal price) {
        BigDecimal best = best(order.getSide(), order.getTrailPeak(), price);
        if (order.getTrailPeak() != null && best.compareTo(order.getTrailPeak()) == 0) {
            return null;
        }
        return new Move(best, stopFor(order.getSide(), best, order.getTrailAmount(), order.getTrailPercent()));
    }

    /**
     * A trailing order's stop moving.
     * @param best the new best price
     * @param stop the new stop price
     */
    public record Move(BigDecimal best, BigDecimal stop) {
    }
}
