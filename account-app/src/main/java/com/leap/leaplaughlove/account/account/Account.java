package com.leap.leaplaughlove.account.account;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entity to represent an account in the system.
 */
@Entity
@Table(name = "accounts", schema = "trading")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "status", nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency;

    @Column(name = "trading_enabled", nullable = false)
    private boolean tradingEnabled;

    @Column(name = "max_slippage_pct", precision = 5, scale = 2)
    private BigDecimal maxSlippagePercent;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected Account() {
    }

    /**
     * Constructor for the Account entity with all fields specified.
     * @param accountId the unique identifier for the account
     * @param clientId the unique identifier for the client owning the account
     * @param accountNumber the unique account number
     * @param status the status of the account
     * @param baseCurrency the base currency of the account
     * @param tradingEnabled flag indicating if trading is enabled for the account
     * @param createdAt the timestamp when the account was created
     */
    public Account(UUID accountId, UUID clientId, String accountNumber, String status,
                   String baseCurrency, boolean tradingEnabled, OffsetDateTime createdAt) {
        this.accountId = accountId;
        this.clientId = clientId;
        this.accountNumber = accountNumber;
        this.status = status;
        this.baseCurrency = baseCurrency;
        this.tradingEnabled = tradingEnabled;
        this.createdAt = createdAt;
    }

    /**
     * Returns the account identifier.
     * @return the unique identifier for the account
     */
    public UUID getAccountId() { return accountId; }

    /**
     * Returns the client identifier.
     * @return the unique identifier for the client owning the account
     */
    public UUID getClientId() { return clientId; }

    /**
     * Returns the account number.
     * @return the unique account number
     */
    public String getAccountNumber() { return accountNumber; }

    /**
     * Returns the account status.
     * @return the status of the account
     */
    public String getStatus() { return status; }

    /**
     * Returns the base currency of the account.
     * @return the base currency of the account
     */
    public String getBaseCurrency() { return baseCurrency; }

    /**
     * Indicates if trading is enabled for the account.
     * @return true if trading is enabled, false otherwise
     */
    public boolean isTradingEnabled() { return tradingEnabled; }

    /**
     * Returns the saved price tolerance: the most, in percent, the price may move either way
     * between an order's quote and its execution before order-app rejects it.
     * @return the tolerance in percent, or null when none is saved
     */
    public BigDecimal getMaxSlippagePercent() { return maxSlippagePercent; }

    /**
     * Saves the account's price tolerance.
     * @param maxSlippagePercent the tolerance in percent, or null to clear it
     */
    public void setMaxSlippagePercent(BigDecimal maxSlippagePercent) { this.maxSlippagePercent = maxSlippagePercent; }

    /**
     * Returns the creation timestamp of the account.
     * @return the timestamp when the account was created
     */
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

