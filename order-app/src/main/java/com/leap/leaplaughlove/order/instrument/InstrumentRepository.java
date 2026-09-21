package com.leap.leaplaughlove.order.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for managing Instrument entities.
 * Provides methods to perform CRUD operations and custom queries on instruments.
 */
@Repository
public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {
    /**
     * Finds an instrument by its trading symbol.
     * @param symbol the trading symbol of the instrument
     * @return an Optional containing the instrument if found, or empty if not found
     */
    Optional<Instrument> findBySymbol(String symbol);
}

