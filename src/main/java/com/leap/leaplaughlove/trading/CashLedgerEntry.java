package com.leap.leaplaughlove.trading;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

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

    @Column(name = "amount", nullable = false)
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

    public CashLedgerEntry(UUID accountId,
                           String entryType,
                           BigDecimal amount,
                           String currency,
                           OffsetDateTime createdAt,
                           String description) {
        this.accountId = accountId;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
        this.description = description;
    }

    public UUID getCashLedgerId() {
        return cashLedgerId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getDescription() {
        return description;
    }
}
