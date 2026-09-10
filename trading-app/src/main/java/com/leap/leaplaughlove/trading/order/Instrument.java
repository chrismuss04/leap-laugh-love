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

    protected Instrument() {
    }

    public Instrument(UUID instrumentId, String symbol, String name, String assetClass) {
        this.instrumentId = instrumentId;
        this.symbol = symbol;
        this.name = name;
        this.assetClass = assetClass;
    }

    public UUID getInstrumentId() { return instrumentId; }
    public String getSymbol() { return symbol; }
    public String getName() { return name; }
    public String getAssetClass() { return assetClass; }
}
