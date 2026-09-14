package com.leap.leaplaughlove.marketdata.instrument;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Interface to represent the repository for simulated instruments in the market data system.
 * This repository provides methods to perform CRUD operations and custom queries on instruments.
 */
public interface SimulatedInstrumentRepository extends JpaRepository<SimulatedInstrument, UUID> {

    /**
     * Finds every instrument currently marked active.
     * @return the active instruments
     */
    List<SimulatedInstrument> findByActiveTrue();
}
