package com.leap.leaplaughlove.order.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only view of trading.accounts for order queries by client.
 */
@Entity
@Table(name = "accounts", schema = "trading")
public class Account {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "base_currency", nullable = false)
    private String baseCurrency;

    @Column(name = "trading_enabled", nullable = false)
    private boolean tradingEnabled;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected Account() {
    }

    /**
     * Constructs an Account with the specified details.
     * 
     * @param accountId      the unique identifier of the account
     * @param clientId       the client ID that owns this account
     * @param accountNumber  the account number
     * @param status         the status of the account
     * @param baseCurrency   the base currency of the account
     * @param tradingEnabled whether trading is enabled for the account
     * @param createdAt      the timestamp when the account was created
     */
    public Account(UUID accountId, UUID clientId, String accountNumber, String status, String baseCurrency, boolean tradingEnabled, OffsetDateTime createdAt) {
        this.accountId = accountId;
        this.clientId = clientId;
        this.accountNumber = accountNumber;
        this.status = status;
        this.baseCurrency = baseCurrency;
        this.tradingEnabled = tradingEnabled;
        this.createdAt = createdAt;
    }

    /**
     * Returns the unique identifier of the account.
     * 
     * @return the account ID
     */
    public UUID getAccountId() {
        return accountId;
    }

    /**
     * Returns the client ID that owns this account.
     * 
     * @return the client ID
     */
    public UUID getClientId() {
        return clientId;
    }

    /**
     * Returns the account number.
     * 
     * @return the account number
     */
    public String getAccountNumber() {
        return accountNumber;
    }

    /**
     * Returns the account status.
     * 
     * @return the account status
     */
    public String getStatus() {
        return status;
    }

    /**
     * Returns the base currency of the account.
     * 
     * @return the base currency
     */
    public String getBaseCurrency() {
        return baseCurrency;
    }

    /**
     * Indicates whether trading is enabled on the account.
     * 
     * @return true if trading is enabled, false otherwise
     */
    public boolean isTradingEnabled() {
        return tradingEnabled;
    }

    /**
     * Returns the timestamp when the account was created.
     * 
     * @return the creation timestamp
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

