package com.leap.leaplaughlove.order.order;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.leap.leaplaughlove.order.account.Account;
import com.leap.leaplaughlove.order.instrument.Instrument;

/**
 * Represents a trading order placed for a specific instrument.
 * Decoupled from Account JPA entity: stores accountId directly.
 * Follows the audit lifecycle: SUBMITTED -> ACCEPTED or REJECTED -> FILLED. Orders other than
 * market orders wait in between: SUBMITTED -> OPEN -> ACCEPTED -> FILLED once the market reaches
 * their price, or OPEN -> CANCELLED or EXPIRED if it never does.
 */
@Entity
@Table(name = "orders", schema = "trading")
public class Order {

    /**
     * Represents the side of the order, either BUY or SELL.
     */
    public enum Side { BUY, SELL }

    /**
     * The status of the order matching the database check constraint. OPEN orders are waiting
     * for the market to reach their price; CANCELLED and EXPIRED ones never executed.
     */
    public enum Status { SUBMITTED, OPEN, ACCEPTED, REJECTED, FILLED, CANCELLED, EXPIRED }

    /**
     * How the order is priced. MARKET fills at once at the live quote. LIMIT fills only at its
     * limit price or better. STOP becomes a market order once the price reaches its stop price,
     * and STOP_LIMIT becomes a limit order then.
     */
    public enum OrderType {
        MARKET, LIMIT, STOP, STOP_LIMIT;

        /**
         * Returns whether this type carries a limit price.
         * @return true for LIMIT and STOP_LIMIT
         */
        public boolean hasLimit() { return this == LIMIT || this == STOP_LIMIT; }

        /**
         * Returns whether this type carries a stop price.
         * @return true for STOP and STOP_LIMIT
         */
        public boolean hasStop() { return this == STOP || this == STOP_LIMIT; }
    }

    /**
     * How long an order that hasn't filled keeps working: DAY until the end of the day it was
     * placed, GTC (good till cancelled) until cancelled, for at most 90 days.
     */
    public enum TimeInForce { DAY, GTC }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Transient
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id", nullable = false)
    private Instrument instrument;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false)
    private Side side;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "rejected_at")
    private OffsetDateTime rejectedAt;

    @Column(name = "filled_at")
    private OffsetDateTime filledAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "quoted_price", precision = 18, scale = 6)
    private BigDecimal quotedPrice;

    @Column(name = "max_slippage_pct", precision = 5, scale = 2)
    private BigDecimal maxSlippagePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false)
    private OrderType orderType = OrderType.MARKET;

    @Column(name = "limit_price", precision = 18, scale = 6)
    private BigDecimal limitPrice;

    @Column(name = "stop_price", precision = 18, scale = 6)
    private BigDecimal stopPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_in_force")
    private TimeInForce timeInForce;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "triggered_at")
    private OffsetDateTime triggeredAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "expired_at")
    private OffsetDateTime expiredAt;

    @Column(name = "reserved_cash", precision = 18, scale = 2)
    private BigDecimal reservedCash;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected Order() {
    }

    /**
     * Constructor for creating a new order at submission time.
     * @param accountId the ID of the account placing the order
     * @param instrument the instrument being traded
     * @param side the side of the order (BUY or SELL)
     * @param quantity the quantity of the instrument to trade
     * @param submittedAt the timestamp when the order was submitted
     */
    public Order(UUID accountId, Instrument instrument, Side side, Long quantity, OffsetDateTime submittedAt) {
        this.accountId = accountId;
        this.instrument = instrument;
        this.side = side;
        this.quantity = quantity;
        this.status = Status.SUBMITTED;
        this.submittedAt = submittedAt != null ? submittedAt : OffsetDateTime.now();
    }

    /**
     * Full constructor for Order entity with all audit fields, updated post execution attempt
     * @param orderId the unique identifier of the order
     * @param accountId the ID of the account placing the order
     * @param instrument the instrument being traded
     * @param side the side of the order (BUY or SELL)
     * @param quantity the quantity of the instrument to trade
     * @param status the current status of the order
     * @param submittedAt the timestamp when the order was submitted
     * @param acceptedAt the timestamp when the order was accepted
     * @param rejectedAt the timestamp when the order was rejected
     * @param filledAt the timestamp when the order was filled
     * @param rejectionReason the reason for order rejection, if applicable
     */
    public Order(UUID orderId, UUID accountId, Instrument instrument, Side side,
                 Long quantity, Status status, OffsetDateTime submittedAt,
                 OffsetDateTime acceptedAt, OffsetDateTime rejectedAt,
                 OffsetDateTime filledAt, String rejectionReason) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.instrument = instrument;
        this.side = side;
        this.quantity = quantity;
        this.status = status;
        this.submittedAt = submittedAt;
        this.acceptedAt = acceptedAt;
        this.rejectedAt = rejectedAt;
        this.filledAt = filledAt;
        this.rejectionReason = rejectionReason;
    }

    /**
     * Constructor for Order entity with Account object reference.
     */
    public Order(UUID orderId, Account account, Instrument instrument, Side side,
                 Long quantity, Status status, OffsetDateTime submittedAt,
                 OffsetDateTime acceptedAt, OffsetDateTime rejectedAt,
                 OffsetDateTime filledAt, String rejectionReason) {
        this(orderId, account != null ? account.getAccountId() : null, instrument, side,
             quantity, status, submittedAt, acceptedAt, rejectedAt, filledAt, rejectionReason);
    }

    /**
     * Sets how the order is priced and how long it may wait. Called once, before the order is
     * first stored.
     * @param orderType the order type
     * @param limitPrice the limit price, for LIMIT and STOP_LIMIT orders
     * @param stopPrice the stop price, for STOP and STOP_LIMIT orders
     * @param timeInForce how long it may wait; null for a market order
     * @param expiresAt when it expires if still OPEN; null for a market order
     */
    public void setConditions(OrderType orderType, BigDecimal limitPrice, BigDecimal stopPrice,
                              TimeInForce timeInForce, OffsetDateTime expiresAt) {
        this.orderType = orderType != null ? orderType : OrderType.MARKET;
        this.limitPrice = limitPrice;
        this.stopPrice = stopPrice;
        this.timeInForce = timeInForce;
        this.expiresAt = expiresAt;
    }

    /**
     * Transitions order state to OPEN: it waits for the market to reach its price.
     * @param reservedCash the cash it holds back from buying power while open; null for a sell
     */
    public void markOpen(BigDecimal reservedCash) {
        this.status = Status.OPEN;
        this.reservedCash = reservedCash;
    }

    /**
     * Records that a stop order's stop price was reached. A STOP_LIMIT order stays OPEN, now
     * working as a limit order. Only the first trigger is kept.
     * @param triggeredAt the timestamp when the stop price was reached
     */
    public void markTriggered(OffsetDateTime triggeredAt) {
        if (this.triggeredAt == null) {
            this.triggeredAt = triggeredAt != null ? triggeredAt : OffsetDateTime.now();
        }
    }

    /**
     * Transitions an OPEN order to CANCELLED at the client's request.
     * @param cancelledAt the timestamp when the order was cancelled
     */
    public void markCancelled(OffsetDateTime cancelledAt) {
        this.status = Status.CANCELLED;
        this.cancelledAt = cancelledAt != null ? cancelledAt : OffsetDateTime.now();
    }

    /**
     * Transitions an OPEN order to EXPIRED once its time in force has run out.
     * @param expiredAt the timestamp when the order expired
     */
    public void markExpired(OffsetDateTime expiredAt) {
        this.status = Status.EXPIRED;
        this.expiredAt = expiredAt != null ? expiredAt : OffsetDateTime.now();
    }

    /**
     * Transitions order state to ACCEPTED.
     * @param acceptedAt the timestamp when the order was accepted
     */
    public void markAccepted(OffsetDateTime acceptedAt) {
        this.status = Status.ACCEPTED;
        this.acceptedAt = acceptedAt != null ? acceptedAt : OffsetDateTime.now();
    }

    /**
     * Transitions order state to REJECTED with a given reason.
     * @param reason the reason for order rejection
     * @param rejectedAt the timestamp when the order was rejected
     */
    public void markRejected(String reason, OffsetDateTime rejectedAt) {
        this.status = Status.REJECTED;
        this.rejectionReason = reason;
        this.rejectedAt = rejectedAt != null ? rejectedAt : OffsetDateTime.now();
    }

    /**
     * Transitions order state to FILLED upon successful execution.
     * @param filledAt the timestamp when the order was filled
     */
    public void markFilled(OffsetDateTime filledAt) {
        this.status = Status.FILLED;
        this.filledAt = filledAt != null ? filledAt : OffsetDateTime.now();
    }

    /**
     * Returns the orderID of this order.
     * @return the orderID
     */
    public UUID getOrderId() { return orderId; }
    /**
     * Returns the accountId associated with this order.
     * @return the accountId
     */
    public UUID getAccountId() {
        return accountId;
    }
    /**
     * Returns the account number associated with this order.
     * @return the account number
     */
    public String getAccountNumber() { return accountNumber; }
    /**
     * Sets the account number for this order.
     * @param accountNumber the account number to set
     */
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    /**
     * Returns the instrument associated with this order.
     * @return the instrument
     */
    public Instrument getInstrument() { return instrument; }
    /**
     * Returns the side (BUY/SELL) of this order.
     * @return the side
     */
    public Side getSide() { return side; }
    /**
     * Returns the quantity of this order.
     * @return the quantity
     */
    public Long getQuantity() { return quantity; }
    /**
     * Returns the status of this order.
     * @return the status
     */
    public Status getStatus() { return status; }
    /**
     * Returns the timestamp when the order was submitted.
     * @return the submitted timestamp
     */
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    /**
     * Returns the timestamp when the order was accepted.
     * @return the accepted timestamp
     */
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    /**
     * Returns the timestamp when the order was rejected.
     * @return the rejected timestamp
     */
    public OffsetDateTime getRejectedAt() { return rejectedAt; }
    /**
     * Returns the timestamp when the order was filled.
     * @return the filled timestamp
     */
    public OffsetDateTime getFilledAt() { return filledAt; }
    /**
     * Returns when the order last changed for good: when it filled, was rejected, cancelled or
     * expired - or, while it is still working, when it was submitted. Order history is listed and
     * filtered by this, matching OrderRepository's queries.
     * @return the completion time, or the submission time while the order is not final
     */
    public OffsetDateTime getCompletedAt() {
        if (filledAt != null) return filledAt;
        if (rejectedAt != null) return rejectedAt;
        if (cancelledAt != null) return cancelledAt;
        if (expiredAt != null) return expiredAt;
        return submittedAt;
    }
    /**
     * Returns the reason for order rejection, if any.
     * @return the rejection reason
     */
    public String getRejectionReason() { return rejectionReason; }
    /**
     * Sets the timestamp when the order was accepted.
     * @param acceptedAt the accepted timestamp to set
     */
    public void setAcceptedAt(OffsetDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    /**
     * Sets the timestamp when the order was rejected.
     * @param rejectedAt the rejected timestamp to set
     */
    public void setRejectedAt(OffsetDateTime rejectedAt) { this.rejectedAt = rejectedAt; }
    /**
     * Sets the timestamp when the order was filled.
     * @param filledAt the filled timestamp to set
     */
    public void setFilledAt(OffsetDateTime filledAt) { this.filledAt = filledAt; }
    /**
     * Sets the reason for order rejection.
     * @param rejectionReason the rejection reason to set
     */
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    /**
     * Returns the price the client was quoted when placing the order, if it carried one.
     * @return the quoted price
     */
    public BigDecimal getQuotedPrice() { return quotedPrice; }
    /**
     * Returns the price tolerance, in percent, the order was checked against, if any.
     * @return the price tolerance in percent
     */
    public BigDecimal getMaxSlippagePercent() { return maxSlippagePercent; }
    /**
     * Records the quote the client placed the order at and the tolerance applied to it.
     * @param quotedPrice the price the client was quoted, or null
     * @param maxSlippagePercent the price tolerance in percent, or null when none applied
     */
    public void setPriceTolerance(BigDecimal quotedPrice, BigDecimal maxSlippagePercent) {
        this.quotedPrice = quotedPrice;
        this.maxSlippagePercent = maxSlippagePercent;
    }
    /**
     * Returns how the order is priced.
     * @return the order type; MARKET for an order stored before order types existed
     */
    public OrderType getOrderType() { return orderType != null ? orderType : OrderType.MARKET; }
    /**
     * Returns the limit price of a LIMIT or STOP_LIMIT order.
     * @return the limit price, or null
     */
    public BigDecimal getLimitPrice() { return limitPrice; }
    /**
     * Returns the stop price of a STOP or STOP_LIMIT order.
     * @return the stop price, or null
     */
    public BigDecimal getStopPrice() { return stopPrice; }
    /**
     * Returns how long the order may wait to fill.
     * @return the time in force, or null for a market order
     */
    public TimeInForce getTimeInForce() { return timeInForce; }
    /**
     * Returns when the order expires if it is still OPEN.
     * @return the expiry, or null for a market order
     */
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    /**
     * Returns when a stop order's stop price was reached.
     * @return the trigger time, or null if not reached
     */
    public OffsetDateTime getTriggeredAt() { return triggeredAt; }
    /**
     * Returns when the order was cancelled.
     * @return the cancellation time, or null
     */
    public OffsetDateTime getCancelledAt() { return cancelledAt; }
    /**
     * Returns when the order expired.
     * @return the expiry time, or null
     */
    public OffsetDateTime getExpiredAt() { return expiredAt; }
    /**
     * Returns the cash an OPEN buy holds back from buying power.
     * @return the reserved cash, or null for a sell or a market order
     */
    public BigDecimal getReservedCash() { return reservedCash; }
}

