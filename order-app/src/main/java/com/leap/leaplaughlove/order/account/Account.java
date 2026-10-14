package com.leap.leaplaughlove.order.account;

import com.leap.leaplaughlove.common.persistence.BaseAccount;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only view of trading.accounts for order queries by client.
 */
@Entity
@Table(name = "accounts", schema = "trading")
public class Account extends BaseAccount {

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
        super(accountId, clientId, accountNumber, status, baseCurrency, tradingEnabled, createdAt);
    }
}
