package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.common.market.MarketCalendar;
import com.leap.leaplaughlove.common.market.MarketClock;
import com.leap.leaplaughlove.common.market.MarketSession;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.submission.OrderSubmissionService;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * Which orders may execute in which market session, and when a DAY order's day ends. Pure
 * functions with no state, shared by order placement, {@link OrderTriggerEngine} and order edits.
 *
 * <ul>
 *     <li>REGULAR (9:30-16:00 ET): every order.</li>
 *     <li>PRE_MARKET and AFTER_HOURS: only LIMIT orders placed for extended hours. Stops don't
 *     trigger and trailing stops don't trail on thin extended-hours prices.</li>
 *     <li>CLOSED: nothing executes; orders wait for the next session they may trade in.</li>
 * </ul>
 */
public final class SessionPolicy {

    private SessionPolicy() {
    }

    /**
     * Returns whether an order may execute in a session.
     * @param order the order
     * @param session the session in force
     * @return true if it may fill (or trigger, or trail) now
     */
    public static boolean canExecute(Order order, MarketSession session) {
        return canExecute(order.getOrderType(), order.isExtendedHours(), session);
    }

    /**
     * Returns whether an order on these terms may execute in a session.
     * @param type the order type
     * @param extendedHours whether it was placed for extended hours
     * @param session the session in force
     * @return true if it may fill (or trigger, or trail) now
     */
    public static boolean canExecute(Order.OrderType type, boolean extendedHours, MarketSession session) {
        return switch (session) {
            case REGULAR -> true;
            case PRE_MARKET, AFTER_HOURS -> type == Order.OrderType.LIMIT && extendedHours;
            case CLOSED -> false;
        };
    }

    /**
     * When a DAY order stops working: at the end of the trading day it can next trade in - the
     * regular close, or the end of after-hours for an extended-hours order. An order placed after
     * that time today (or on a weekend or holiday) is good for the next trading day.
     * @param placedAt when the order is placed
     * @param extendedHours whether it trades in extended hours
     * @return its expiry
     */
    public static OffsetDateTime dayExpiry(OffsetDateTime placedAt, boolean extendedHours) {
        MarketCalendar.TradingDay day = MarketCalendar.tradingDay(
                placedAt.atZoneSameInstant(MarketCalendar.ZONE).toLocalDate());
        if (day.isOpen() && placedAt.toInstant().isBefore(endOf(day, extendedHours).toInstant())) {
            return endOf(day, extendedHours).toOffsetDateTime();
        }
        MarketCalendar.TradingDay next = MarketCalendar.nextTradingDay(day.date().plusDays(1));
        return endOf(next, extendedHours).toOffsetDateTime();
    }

    /**
     * When an order with this time in force stops working, if it hasn't filled.
     * @param timeInForce DAY or GTC
     * @param placedAt when it is placed (or its time in force changed)
     * @param extendedHours whether it trades in extended hours
     * @param marketClock the market clock; with market hours off, a DAY order lasts until midnight ET
     * @return its expiry
     */
    public static OffsetDateTime expiryOf(Order.TimeInForce timeInForce, OffsetDateTime placedAt,
                                          boolean extendedHours, MarketClock marketClock) {
        if (timeInForce == Order.TimeInForce.GTC || marketClock.mode() == MarketClock.Mode.ALWAYS_OPEN) {
            return OrderSubmissionService.expiryOf(timeInForce, placedAt);
        }
        return dayExpiry(placedAt, extendedHours);
    }

    private static ZonedDateTime endOf(MarketCalendar.TradingDay day, boolean extendedHours) {
        return extendedHours ? day.afterHoursClose() : day.regularClose();
    }

    /**
     * Why a market order can't be placed outside regular hours, for its rejection.
     * @param session the session in force (not REGULAR)
     * @return the reason
     */
    public static String marketOrderClosedReason(MarketSession session) {
        String now = switch (session) {
            case PRE_MARKET -> "It's pre-market now";
            case AFTER_HOURS -> "It's after-hours now";
            default -> "The market is closed now";
        };
        String alternative = session.isExtendedHours()
                ? "place a limit order with extended hours on, or try again at the open"
                : "place a limit order to work at the open, or try again then";
        return "Market orders can only be placed during regular market hours (9:30 AM - 4:00 PM ET). "
                + now + ": " + alternative + " - order rejected";
    }
}
