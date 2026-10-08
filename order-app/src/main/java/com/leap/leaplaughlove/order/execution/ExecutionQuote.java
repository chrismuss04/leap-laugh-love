package com.leap.leaplaughlove.order.execution;

import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * The market quote an execution was priced from, kept for audit.
 */
@Embeddable
public class ExecutionQuote {

    @Column(name = "quote_bid", precision = 18, scale = 6)
    private BigDecimal bid;

    @Column(name = "quote_ask", precision = 18, scale = 6)
    private BigDecimal ask;

    @Column(name = "quote_last", precision = 18, scale = 6)
    private BigDecimal last;

    @Column(name = "quote_timestamp")
    private OffsetDateTime quotedAt;

    @Column(name = "quote_exchange")
    private String exchange;

    protected ExecutionQuote() {
    }

    public ExecutionQuote(BigDecimal bid, BigDecimal ask, BigDecimal last, OffsetDateTime quotedAt, String exchange) {
        this.bid = bid;
        this.ask = ask;
        this.last = last;
        this.quotedAt = quotedAt;
        this.exchange = exchange;
    }

    public static ExecutionQuote of(QuoteSnapshot quote) {
        return new ExecutionQuote(quote.bidPrice(), quote.askPrice(), quote.lastPrice(),
                quote.quoteTimestamp(), quote.exchange());
    }

    /**
     * A BUY executes at the ask and a SELL at the bid, falling back to the last trade.
     * @return the price to 4 decimal places, or null if the quote has no positive price
     */
    public BigDecimal executablePrice(Order.Side side) {
        BigDecimal sidePrice = side == Order.Side.BUY ? ask : bid;
        BigDecimal price = isPositive(sidePrice) ? sidePrice : last;
        return isPositive(price) ? price.setScale(4, RoundingMode.HALF_UP) : null;
    }

    private static boolean isPositive(BigDecimal price) {
        return price != null && price.compareTo(BigDecimal.ZERO) > 0;
    }

    public BigDecimal getBid() { return bid; }

    public BigDecimal getAsk() { return ask; }

    public BigDecimal getLast() { return last; }

    public OffsetDateTime getQuotedAt() { return quotedAt; }

    public String getExchange() { return exchange; }
}
