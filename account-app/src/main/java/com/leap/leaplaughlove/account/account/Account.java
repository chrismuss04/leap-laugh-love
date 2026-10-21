package com.leap.leaplaughlove.account.account;

import com.leap.leaplaughlove.common.persistence.BaseAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entity to represent an account in the system.
 */
@Entity
@Table(name = "accounts", schema = "trading")
public class Account extends BaseAccount {

    @Column(name = "max_slippage_pct", precision = 5, scale = 2)
    private BigDecimal maxSlippagePercent;

    @Column(name = "inactive_since")
    private OffsetDateTime inactiveSince;

    @Column(name = "inactive_notified_at")
    private OffsetDateTime inactiveNotifiedAt;

    @Column(name = "account_seq")
    private Integer accountSeq;

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
        super(accountId, clientId, accountNumber, status, baseCurrency, tradingEnabled, createdAt);
    }

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

    /** @return when the account became empty, if the inactivity job has flagged it; otherwise null */
    public OffsetDateTime getInactiveSince() { return inactiveSince; }

    /** Flags the account inactive since the given time, or clears the flag with null. */
    public void setInactiveSince(OffsetDateTime inactiveSince) { this.inactiveSince = inactiveSince; }

    /** @return when the client was emailed about the current inactive period; null until then */
    public OffsetDateTime getInactiveNotifiedAt() { return inactiveNotifiedAt; }

    /** Records when the client was emailed about the inactive account, or clears it with null. */
    public void setInactiveNotifiedAt(OffsetDateTime inactiveNotifiedAt) { this.inactiveNotifiedAt = inactiveNotifiedAt; }

    /** @return the account's place among its client's accounts (1, 2, ...), or null if it was never numbered */
    public Integer getAccountSeq() { return accountSeq; }

    /** Numbers the account among its client's accounts; shown to the client as "Brokerage N". */
    public void setAccountSeq(Integer accountSeq) { this.accountSeq = accountSeq; }
}
