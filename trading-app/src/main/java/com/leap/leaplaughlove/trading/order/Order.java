package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "orders", schema = "trading")
public class Order {

    public enum Side { BUY, SELL }
    public enum Type { MARKET, LIMIT }
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

    protected Order() {
    }

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

    public UUID getOrderId() { return orderId; }
    public Account getAccount() { return account; }
    public Instrument getInstrument() { return instrument; }
    public Side getSide() { return side; }
    public Type getType() { return type; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public Status getStatus() { return status; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public OffsetDateTime getFilledAt() { return filledAt; }
}
