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

    /** Default constructor for JPA. */
    protected ExecutionQuote() {
    }

    /**
     * Constructs a new ExecutionQuote.
     * @param bid the best bid, or null
     * @param ask the best ask, or null
     * @param last the last traded price, or null
     * @param quotedAt when the market data provider produced the quote
     * @param exchange the exchange the quote came from
     */
    public ExecutionQuote(BigDecimal bid, BigDecimal ask, BigDecimal last, OffsetDateTime quotedAt, String exchange) {
        this.bid = bid;
        this.ask = ask;
        this.last = last;
        this.quotedAt = quotedAt;
        this.exchange = exchange;
    }

    /**
     * Captures the quote an execution was priced from.
     * @param quote the market data snapshot
     * @return the quote as stored with the execution
     */
    public static ExecutionQuote of(QuoteSnapshot quote) {
        return new ExecutionQuote(quote.bidPrice(), quote.askPrice(), quote.lastPrice(),
                quote.quoteTimestamp(), quote.exchange());
    }

    /**
     * A BUY executes at the ask and a SELL at the bid, falling back to the last trade.
     * @param side the side of the order being priced
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

    /**
     * Returns the best bid at the time of the quote, or null if the quote had none.
     * @return the best bid at the time of the quote, or null if the quote had none
     */
    public BigDecimal getBid() { return bid; }

    /**
     * Returns the best ask at the time of the quote, or null if the quote had none.
     * @return the best ask at the time of the quote, or null if the quote had none
     */
    public BigDecimal getAsk() { return ask; }

    /**
     * Returns the last traded price at the time of the quote, or null if the quote had none.
     * @return the last traded price at the time of the quote, or null if the quote had none
     */
    public BigDecimal getLast() { return last; }

    /**
     * Returns when the market data provider produced the quote.
     * @return when the market data provider produced the quote
     */
    public OffsetDateTime getQuotedAt() { return quotedAt; }

    /**
     * Returns the exchange the quote came from.
     * @return the exchange the quote came from
     */
    public String getExchange() { return exchange; }
}
