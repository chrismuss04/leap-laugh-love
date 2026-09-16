package com.leap.leaplaughlove.trading.position;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PositionMovementRepository extends JpaRepository<PositionMovement, UUID> {
    List<PositionMovement> findByAccountIdAndInstrumentId(UUID accountId, UUID instrumentId);
}

