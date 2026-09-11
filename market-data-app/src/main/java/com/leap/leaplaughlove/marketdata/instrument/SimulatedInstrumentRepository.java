package com.leap.leaplaughlove.marketdata.instrument;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SimulatedInstrumentRepository extends JpaRepository<SimulatedInstrument, UUID> {
    List<SimulatedInstrument> findByActiveTrue();
}
