package com.leap.leaplaughlove.order.position;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
     * Checks whether the order's fill has been written to the ledger.
     * @param orderId the order ID
     * @return true if a movement exists for the order
     */
    boolean existsByOrderId(UUID orderId);
}

