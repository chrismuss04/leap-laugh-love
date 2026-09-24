package com.leap.leaplaughlove.account.position;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository interface for managing PositionMovement entities.
 */
@Repository
public interface PositionMovementRepository extends JpaRepository<PositionMovement, UUID> {
    /**
     * Finds all position movements for the given account and instrument.
     * @param accountId the ID of the account
     * @param instrumentId the ID of the instrument
     * @return a list of position movements matching the given account and instrument
     */
    List<PositionMovement> findByAccountIdAndInstrumentId(UUID accountId, UUID instrumentId);

    /**
     * Finds every movement on the given accounts recorded after a point in time, used to walk
     * current holdings back to what they were at that time.
     * @param accountIds the collection of account IDs
     * @param after the timestamp threshold
     * @return the matching position movements
     */
    List<PositionMovement> findByAccountIdInAndCreatedAtAfter(Collection<UUID> accountIds, OffsetDateTime after);
}

