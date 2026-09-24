package com.leap.leaplaughlove.account.ledger;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entity to represent an entry into the cash ledger.
 * Immutable audit record tracking deposits, withdrawals, and trade settlements.
 */
@Entity
@Table(name = "cash_ledger", schema = "trading")
public class CashLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "cash_ledger_id")
    private UUID cashLedgerId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "entry_type", nullable = false)
    private String entryType;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "description")
    private String description;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected CashLedgerEntry() {
    }

    /**
     * Constructs a new CashLedgerEntry with the specified details.
     * @param accountId the unique identifier of the account
     * @param entryType the type of the cash transaction (e.g., deposit, withdrawal)
     * @param amount the amount of the cash transaction
     * @param currency the currency of the cash transaction
     * @param createdAt the timestamp when the transaction was created
     * @param description a description of the cash transaction
     */
    public CashLedgerEntry(UUID accountId, String entryType, BigDecimal amount, String currency,
                           OffsetDateTime createdAt, String description) {
        this(accountId, null, null, entryType, amount, currency, createdAt, description);
    }

    /**
     * Constructs a new CashLedgerEntry with the specified details, including optional order and execution IDs.
     * @param accountId the unique identifier of the account
     * @param orderId the unique identifier of the order associated with the transaction (nullable)
     * @param executionId the unique identifier of the execution associated with the transaction (nullable)
     * @param entryType the type of the cash transaction (e.g., deposit, withdrawal)
     * @param amount the amount of the cash transaction
     * @param currency the currency of the cash transaction
     * @param createdAt the timestamp when the transaction was created
     * @param description a description of the cash transaction
     */
    public CashLedgerEntry(UUID accountId, UUID orderId, UUID executionId, String entryType,
                           BigDecimal amount, String currency, OffsetDateTime createdAt, String description) {
        this.accountId = accountId;
        this.orderId = orderId;
        this.executionId = executionId;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
        this.description = description;
    }

    /**
     * Returns the unique identifier of the cash ledger entry.
     * @return the cash ledger ID
     */
    public UUID getCashLedgerId() { return cashLedgerId; }
    /**
     * Returns the unique identifier of the account associated with the cash ledger entry.
     * @return the account ID
     */
    public UUID getAccountId() { return accountId; }
    /**
     * Returns the unique identifier of the order associated with the cash ledger entry.
     * @return the order ID (nullable)
     */
    public UUID getOrderId() { return orderId; }
    /**
     * Returns the unique identifier of the execution associated with the cash ledger entry.
     * @return the execution ID (nullable)
     */
    public UUID getExecutionId() { return executionId; }
    /**
     * Returns the type of the cash transaction.
     * @return the entry type
     */
    public String getEntryType() { return entryType; }
    /**
     * Returns the amount of the cash transaction.
     * @return the amount
     */
    public BigDecimal getAmount() { return amount; }
    /**
     * Returns the currency of the cash transaction.
     * @return the currency
     */
    public String getCurrency() { return currency; }
    /**
     * Returns the timestamp when the transaction was created.
     * @return the creation timestamp
     */
    public OffsetDateTime getCreatedAt() { return createdAt; }
    /**
     * Returns the description of the cash transaction.
     * @return the description
     */
    public String getDescription() { return description; }
}

