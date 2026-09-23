package com.leap.leaplaughlove.order.order;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.leap.leaplaughlove.order.account.Account;
import com.leap.leaplaughlove.order.instrument.Instrument;

/**
 * Represents a trading order placed for a specific instrument.
 * Decoupled from Account JPA entity: stores accountId directly.
 * Follows the audit lifecycle: SUBMITTED -> ACCEPTED or REJECTED -> FILLED.
 */
@Entity
@Table(name = "orders", schema = "trading")
public class Order {

    /**
     * Represents the side of the order, either BUY or SELL.
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

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    private Account account;

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
    public UUID getAccountId() { return accountId; }
    /**
     * Returns the account entity associated with this order.
     * @return the account
     */
    public Account getAccount() { return account; }
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
}

