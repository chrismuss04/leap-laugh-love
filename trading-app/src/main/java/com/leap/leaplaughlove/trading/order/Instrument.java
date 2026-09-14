package com.leap.leaplaughlove.trading.order;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Represents a financial instrument in the trading system.
 */
@Entity
@Table(name = "instruments", schema = "trading")
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "instrument_id")
    private UUID instrumentId;

    @Column(name = "symbol", nullable = false, unique = true)
    private String symbol;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "asset_class", nullable = false)
    private String assetClass;

    /**
     * No-argument constructor for use by JPA.
     */
    protected Instrument() {
    }

    /**
     * Method to create a new Instrument entity with the specified details.
     * @param instrumentId the unique identifier of the instrument
     * @param symbol the trading symbol of the instrument
     * @param name the name of the instrument
     * @param assetClass the asset class of the instrument (e.g., equity, bond)
     */
    public Instrument(UUID instrumentId, String symbol, String name, String assetClass) {
        this.instrumentId = instrumentId;
        this.symbol = symbol;
        this.name = name;
        this.assetClass = assetClass;
    }

    /**
     * Retrieves the unique identifier of the instrument.
     * @return the instrumentId
     */
    public UUID getInstrumentId() { return instrumentId; }
    /**
     * Retrieves the trading symbol of the instrument.
     * @return the symbol
     */
    public String getSymbol() { return symbol; }

    /**
     * Retrieves the name of the instrument.
     * @return the name
     */
    public String getName() { return name; }

    /**
     * Retrieves the asset class of the instrument.
     * @return the assetClass
     */
    public String getAssetClass() { return assetClass; }
}
