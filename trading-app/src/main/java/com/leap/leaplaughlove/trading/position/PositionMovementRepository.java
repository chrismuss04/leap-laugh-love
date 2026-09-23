package com.leap.leaplaughlove.trading.position;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PositionMovementRepository extends JpaRepository<PositionMovement, UUID> {
    List<PositionMovement> findByAccountIdAndInstrumentId(UUID accountId, UUID instrumentId);

    /**
     * Finds every movement on the given accounts recorded after a point in time, used to walk
     * current holdings back to what they were at that time.
     */
    List<PositionMovement> findByAccountIdInAndCreatedAtAfter(Collection<UUID> accountIds, OffsetDateTime after);
}

