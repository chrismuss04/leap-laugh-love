package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents a trading order placed by an account for a specific instrument.
 * Follows the audit lifecycle: SUBMITTED -> ACCEPTED or REJECTED -> FILLED.
 */
@Entity
@Table(name = "orders", schema = "trading")
public class Order {

    /**
     * The side of the order (BUY or SELL).
     */
    public enum Side { BUY, SELL }

    /**
     * The status of the order matching the database check constraint:
     * SUBMITTED, ACCEPTED, REJECTED, FILLED.
     */
    public enum Status { SUBMITTED, ACCEPTED, REJECTED, FILLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "order_id")
    private UUID orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

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

    /**
     * Protected no-argument constructor for JPA.
     */
    protected Order() {
    }

    /**
     * Constructor for creating a new order at submission time.
     */
    public Order(Account account, Instrument instrument, Side side, Long quantity, OffsetDateTime submittedAt) {
        this.account = account;
        this.instrument = instrument;
        this.side = side;
        this.quantity = quantity;
        this.status = Status.SUBMITTED;
        this.submittedAt = submittedAt != null ? submittedAt : OffsetDateTime.now();
    }

    /**
     * Full constructor for Order entity with all audit fields.
     */
    public Order(UUID orderId, Account account, Instrument instrument, Side side,
                 Long quantity, Status status, OffsetDateTime submittedAt,
                 OffsetDateTime acceptedAt, OffsetDateTime rejectedAt,
                 OffsetDateTime filledAt, String rejectionReason) {
        this.orderId = orderId;
        this.account = account;
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
     * Transitions order state to ACCEPTED.
     */
    public void markAccepted(OffsetDateTime acceptedAt) {
        this.status = Status.ACCEPTED;
        this.acceptedAt = acceptedAt != null ? acceptedAt : OffsetDateTime.now();
    }

    /**
     * Transitions order state to REJECTED with a given reason.
     */
    public void markRejected(String reason, OffsetDateTime rejectedAt) {
        this.status = Status.REJECTED;
        this.rejectionReason = reason;
        this.rejectedAt = rejectedAt != null ? rejectedAt : OffsetDateTime.now();
    }

    /**
     * Transitions order state to FILLED upon successful execution.
     */
    public void markFilled(OffsetDateTime filledAt) {
        this.status = Status.FILLED;
        this.filledAt = filledAt != null ? filledAt : OffsetDateTime.now();
    }

    public UUID getOrderId() { return orderId; }
    public Account getAccount() { return account; }
    public Instrument getInstrument() { return instrument; }
    public Side getSide() { return side; }
    public Long getQuantity() { return quantity; }
    public Status getStatus() { return status; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public OffsetDateTime getRejectedAt() { return rejectedAt; }
    public OffsetDateTime getFilledAt() { return filledAt; }
    public String getRejectionReason() { return rejectionReason; }

    public void setAcceptedAt(OffsetDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public void setRejectedAt(OffsetDateTime rejectedAt) { this.rejectedAt = rejectedAt; }
    public void setFilledAt(OffsetDateTime filledAt) { this.filledAt = filledAt; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
}
