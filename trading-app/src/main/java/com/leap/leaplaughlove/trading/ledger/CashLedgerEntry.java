package com.leap.leaplaughlove.trading.ledger;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entity to represent an entry into the cash ledger
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

    @Column(name = "entry_type", nullable = false)
    private String entryType;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "description")
    private String description;

    protected CashLedgerEntry() {
    }

    /**
     * Constructs a new CashLedgerEntry entity with the specified details.
     * @param accountId the unique identifier of the account associated with this ledger entry
     * @param entryType the type of the ledger entry (e.g., deposit, withdrawal)
     * @param amount the amount of the ledger entry
     * @param currency the currency of the ledger entry
     * @param createdAt the timestamp when the ledger entry was created
     * @param description an optional description for the ledger entry
     */
    public CashLedgerEntry(UUID accountId, String entryType, BigDecimal amount, String currency,
                           OffsetDateTime createdAt, String description) {
        this.accountId = accountId;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
        this.description = description;
    }
    /**
     * Gets the unique identifier of this cash ledger entry.
     * @return the cash ledger entry ID
     */
    public UUID getCashLedgerId() { return cashLedgerId; }
    /**
     * Gets the unique identifier of the account associated with this ledger entry.
     * @return the account ID
     */
    public UUID getAccountId() { return accountId; }

    /**
     * Gets the type of this ledger entry (e.g., deposit, withdrawal).
     * @return the entry type
     */
    public String getEntryType() { return entryType; }

    /**
     * Gets the amount of this ledger entry.
     * @return the amount
     */
    public BigDecimal getAmount() { return amount; }

    /**
     * Gets the currency of this ledger entry.
     * @return the currency
     */
    public String getCurrency() { return currency; }

    /**
     * Gets the timestamp when this ledger entry was created.
     * @return the creation timestamp
     */
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /**
     * Gets the description of this ledger entry.
     * @return the description
     */
    public String getDescription() { return description; }
}
