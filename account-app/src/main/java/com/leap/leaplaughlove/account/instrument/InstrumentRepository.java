package com.leap.leaplaughlove.account.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for managing Instrument entities.
 */
@Repository
public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {
    /**
     * Finds an instrument by its symbol and active tradable state.
     * @param symbol the trading symbol
     * @return an Optional containing the instrument if found
     */
    Optional<Instrument> findBySymbolAndIsTradableTrue(String symbol);
}

