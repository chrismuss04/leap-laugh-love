package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "orders", schema = "trading")
public class Order {

    public enum Side { BUY, SELL }
    public enum Status { SUBMITTED, ACCEPTED, REJECTED, FILLED }

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

    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt;

    @Column(name = "filled_at")
    private OffsetDateTime filledAt;

    protected Order() {
    }

    public Order(UUID orderId, Account account, Instrument instrument, Side side,
                 BigDecimal quantity, Status status,
                 OffsetDateTime submittedAt, OffsetDateTime filledAt) {
        this.orderId = orderId;
        this.account = account;
        this.instrument = instrument;
        this.side = side;
        this.quantity = quantity;
        this.status = status;
        this.submittedAt = submittedAt;
        this.filledAt = filledAt;
    }

    public UUID getOrderId() { return orderId; }
    public Instrument getInstrument() { return instrument; }
    public Side getSide() { return side; }
    public BigDecimal getQuantity() { return quantity; }
    public Status getStatus() { return status; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public OffsetDateTime getFilledAt() { return filledAt; }
}
