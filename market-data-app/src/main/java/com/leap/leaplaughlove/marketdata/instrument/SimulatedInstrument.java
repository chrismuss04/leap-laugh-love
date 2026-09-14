package com.leap.leaplaughlove.marketdata.instrument;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

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

    public UUID getInstrumentId() { return instrumentId; }
    public String getSymbol() { return symbol; }
    public String getDisplayName() { return displayName; }
    public BigDecimal getInitialPrice() { return initialPrice; }
    public BigDecimal getDrift() { return drift; }
    public BigDecimal getVolatility() { return volatility; }
    public long getRngSeed() { return rngSeed; }
    public boolean isActive() { return active; }
}
