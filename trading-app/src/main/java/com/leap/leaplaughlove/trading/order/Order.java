package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents a trading order placed by an account for a specific instrument.
 * Contains details such as side, type, quantity, limit price, status, and timestamps.
 */
@Entity
@Table(name = "orders", schema = "trading")
public class Order {

    /**
     * The side of the order (BUY or SELL).
     */
    public enum Side { BUY, SELL }
    /**
     * The type of the order (MARKET or LIMIT).
     */
    public enum Type { MARKET, LIMIT }
    /**
     * The status of the order (PENDING, FILLED, PARTIALLY_FILLED, CANCELLED, REJECTED).
     */
    public enum Status { PENDING, FILLED, PARTIALLY_FILLED, CANCELLED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "order_id")
    private UUID orderId;

    @ManyToOne
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne
    @JoinColumn(name = "instrument_id", nullable = false)
    private Instrument instrument;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false)
    private Side side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false)
    private Type type;

    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @Column(name = "limit_price", precision = 15, scale = 4)
    private BigDecimal limitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt;

    @Column(name = "filled_at")
    private OffsetDateTime filledAt;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected Order() {
    }

    /**
     * Method to create a new Order entity with the specified details.
     * @param orderId the unique identifier of the order
     * @param account the account placing the order
     * @param instrument the instrument being traded
     * @param side the side of the order (BUY or SELL)
     * @param type the type of the order (MARKET or LIMIT)
     * @param quantity the quantity of the order
     * @param limitPrice the limit price of the order (if applicable)
     * @param status the current status of the order
     * @param submittedAt the timestamp when the order was submitted
     * @param filledAt the timestamp when the order was filled (if applicable)
     */
    public Order(UUID orderId, Account account, Instrument instrument, Side side, Type type,
                 BigDecimal quantity, BigDecimal limitPrice, Status status,
                 OffsetDateTime submittedAt, OffsetDateTime filledAt) {
        this.orderId = orderId;
        this.account = account;
        this.instrument = instrument;
        this.side = side;
        this.type = type;
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.status = status;
        this.submittedAt = submittedAt;
        this.filledAt = filledAt;
    }
    
    /**
     * Gets the unique identifier of the order.
     * @return the orderId of the order
     */
    public UUID getOrderId() { return orderId; }
    /**
     * Gets the account placing the order.
     * @return the account of the order
     */
    public Account getAccount() { return account; }
    /**
     * Gets the instrument being traded in the order.
     * @return the instrument of the order
     */
    public Instrument getInstrument() { return instrument; }
    /**
     * Gets the side of the order (BUY or SELL).
     * @return the side of the order
     */
    public Side getSide() { return side; }
    /**
     * Gets the type of the order (MARKET or LIMIT).
     * @return the type of the order
     */
    public Type getType() { return type; }
    /**
     * Gets the quantity of the order.
     * @return the quantity of the order
     */
    public BigDecimal getQuantity() { return quantity; }
    /**
     * Gets the limit price of the order (if applicable).
     * @return the limit price of the order
     */
    public BigDecimal getLimitPrice() { return limitPrice; }
    /**
     * Gets the current status of the order.
     * @return the status of the order
     */
    public Status getStatus() { return status; }
    /**
     * Gets the timestamp when the order was submitted.
     * @return the submittedAt timestamp of the order
     */
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    /**
     * Gets the timestamp when the order was filled (if applicable).
     * @return the filledAt timestamp of the order
     */
    public OffsetDateTime getFilledAt() { return filledAt; }
}
