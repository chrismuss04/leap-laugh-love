package com.leap.leaplaughlove.common.market;

/**
 * The US equity market's trading sessions, in US Eastern time: pre-market 4:00-9:30, regular
 * 9:30-16:00 (13:00 on early-close days), after-hours until 20:00 (17:00 on early-close days),
 * and closed overnight, at weekends and on exchange holidays.
 */
public enum MarketSession {
    PRE_MARKET, REGULAR, AFTER_HOURS, CLOSED;

    /**
     * Returns whether any trading happens in this session.
     * @return true for every session but CLOSED
     */
    public boolean isTrading() {
        return this != CLOSED;
    }

    /**
     * Returns whether this is an extended-hours session, where only extended-hours limit orders trade.
     * @return true for PRE_MARKET and AFTER_HOURS
     */
    public boolean isExtendedHours() {
        return this == PRE_MARKET || this == AFTER_HOURS;
    }
}
