package com.leap.leaplaughlove.marketdata.instrument;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Represents the GBM simulation parameters for one instrument: its starting price, drift,
 * volatility, and reproducibility seed.
 */
@Entity
@Table(name = "instruments", schema = "marketdata")
public class SimulatedInstrument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "instrument_id")
    private UUID instrumentId;

    @Column(name = "symbol", nullable = false, unique = true)
    private String symbol;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "initial_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal initialPrice;

    @Column(name = "drift", nullable = false, precision = 9, scale = 6)
    private BigDecimal drift;

    @Column(name = "volatility", nullable = false, precision = 9, scale = 6)
    private BigDecimal volatility;

    @Column(name = "rng_seed", nullable = false)
    private long rngSeed;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected SimulatedInstrument() {
    }

    /**
     * Creates a new SimulatedInstrument entity with the specified details.
     * @param instrumentId the unique identifier of the instrument
     * @param symbol the instrument symbol
     * @param displayName the human-readable display name of the instrument
     * @param initialPrice the starting price for the simulation
     * @param drift the annualized drift used by the GBM simulation
     * @param volatility the annualized volatility used by the GBM simulation
     * @param rngSeed the seed used to make the simulated path reproducible
     * @param active whether the instrument is actively simulated
     */
    public SimulatedInstrument(UUID instrumentId, String symbol, String displayName, BigDecimal initialPrice,
                                BigDecimal drift, BigDecimal volatility, long rngSeed, boolean active) {
        this.instrumentId = instrumentId;
        this.symbol = symbol;
        this.displayName = displayName;
        this.initialPrice = initialPrice;
        this.drift = drift;
        this.volatility = volatility;
        this.rngSeed = rngSeed;
        this.active = active;
    }

    /**
     * Gets the unique identifier of the instrument.
     * @return the instrumentId of the instrument
     */
    public UUID getInstrumentId() { return instrumentId; }
    /**
     * Gets the instrument symbol.
     * @return the symbol of the instrument
     */
    public String getSymbol() { return symbol; }
    /**
     * Gets the human-readable display name of the instrument.
     * @return the displayName of the instrument
     */
    public String getDisplayName() { return displayName; }
    /**
     * Gets the starting price for the simulation.
     * @return the initialPrice of the instrument
     */
    public BigDecimal getInitialPrice() { return initialPrice; }
    /**
     * Gets the annualized drift used by the GBM simulation.
     * @return the drift of the instrument
     */
    public BigDecimal getDrift() { return drift; }
    /**
     * Gets the annualized volatility used by the GBM simulation.
     * @return the volatility of the instrument
     */
    public BigDecimal getVolatility() { return volatility; }
    /**
     * Gets the seed used to make the simulated path reproducible.
     * @return the rngSeed of the instrument
     */
    public long getRngSeed() { return rngSeed; }
    /**
     * Gets whether the instrument is actively simulated.
     * @return true if the instrument is active
     */
    public boolean isActive() { return active; }
}
