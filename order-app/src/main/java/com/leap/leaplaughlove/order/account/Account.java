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

    protected Account() {
    }

    public Account(UUID accountId, UUID clientId, String accountNumber, String status, String baseCurrency, boolean tradingEnabled, OffsetDateTime createdAt) {
        this.accountId = accountId;
        this.clientId = clientId;
        this.accountNumber = accountNumber;
        this.status = status;
        this.baseCurrency = baseCurrency;
        this.tradingEnabled = tradingEnabled;
        this.createdAt = createdAt;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getClientId() {
        return clientId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getStatus() {
        return status;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public boolean isTradingEnabled() {
        return tradingEnabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

