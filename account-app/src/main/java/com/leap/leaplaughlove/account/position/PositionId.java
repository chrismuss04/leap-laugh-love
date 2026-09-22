package com.leap.leaplaughlove.account.position;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents the composite primary key for a trading position, consisting of an account ID and an instrument ID.
 */
public class PositionId implements Serializable {

    private UUID accountId;
    private UUID instrumentId;

    /**
     * Default constructor for JPA
     */
    public PositionId() {
    }

    /**
     * Constructs a position ID key with the specified account ID and instrument ID.
     * @param accountId the unique identifier of the account
     * @param instrumentId the unique identifier of the instrument
     */
    public PositionId(UUID accountId, UUID instrumentId) {
        this.accountId = accountId;
        this.instrumentId = instrumentId;
    }

    /**
     * Retrieves the account identifier of this position key.
     * @return the account identifier
     */
    public UUID getAccountId() {
        return accountId;
    }

    /**
     * Retrieves the instrument identifier of this position key.
     * @return the instrument identifier
     */
    public UUID getInstrumentId() {
        return instrumentId;
    }

    /**    
     * Overrides the equals method to compare position keys based on account ID and instrument ID.
     * @see java.lang.Object#equals(java.lang.Object)
     * @param o the object to compare with
     * @return true if the objects are equal, false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PositionId that)) {
            return false;
        }
        return Objects.equals(accountId, that.accountId)
                && Objects.equals(instrumentId, that.instrumentId);
    }

    /**
     * Overrides the hashCode method to generate a hash code based on account ID and instrument ID.
     * @see java.lang.Object#hashCode()
     * @return the hash code of this position key
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountId, instrumentId);
    }
}

