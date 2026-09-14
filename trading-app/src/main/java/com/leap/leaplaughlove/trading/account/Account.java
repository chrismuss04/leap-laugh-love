package com.leap.leaplaughlove.trading.account;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entity to represent a trading account in the system.
 *
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

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Account() {
    }

    /**
     * Constructs a new Account entity with the specified details.
     * @param accountId the unique identifier of the account
     * @param clientId the unique identifier of the client who owns the account
     * @param accountNumber the account number
     * @param status the status of the account (e.g., active, inactive)
     * @param baseCurrency the base currency of the account
     * @param tradingEnabled indicates whether trading is enabled for the account
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
     * Gets the unique identifier of the account.
     * @return the account ID
     */
    public UUID getAccountId() { return accountId; }
    /**
     * Gets the unique identifier of the client who owns the account.
     * @return the client ID
     */
    public UUID getClientId() { return clientId; }
    /**
     * Gets the account number.
     * @return the account number
     */
    public String getAccountNumber() { return accountNumber; }
    /**
     * Gets the status of the account.
     * @return the account status
     */
    public String getStatus() { return status; }
    /**
     * Gets the base currency of the account.
     * @return the base currency
     */
    public String getBaseCurrency() { return baseCurrency; }
    /**
     * Checks if trading is enabled for the account.
     * @return true if trading is enabled, false otherwise
     */
    public boolean isTradingEnabled() { return tradingEnabled; }
    /**
     * Gets the timestamp when the account was created.
     * @return the creation timestamp
     */
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
