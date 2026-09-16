package com.leap.leaplaughlove.trading.order;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents a financial instrument in the trading system (trading.instruments).
 */
@Entity
@Table(name = "instruments", schema = "trading")
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "instrument_id")
    private UUID instrumentId;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "instrument_name", nullable = false)
    private String instrumentName;

    @Column(name = "asset_class", nullable = false)
    private String assetClass;

    @Column(name = "market", nullable = false)
    private String market = "NASDAQ";

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "is_tradable", nullable = false)
    private boolean isTradable = true;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    /**
     * No-argument constructor for use by JPA.
     */
    protected Instrument() {
    }

    /**
     * Full constructor with all trading instrument details.
     */
    public Instrument(UUID instrumentId, String symbol, String instrumentName, String assetClass,
                      String market, String currency, boolean isTradable) {
        this.instrumentId = instrumentId;
        this.symbol = symbol;
        this.instrumentName = instrumentName;
        this.assetClass = assetClass;
        this.market = market;
        this.currency = currency;
        this.isTradable = isTradable;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getInstrumentId() { return instrumentId; }
    public String getSymbol() { return symbol; }
    public String getInstrumentName() { return instrumentName; }
    public String getAssetClass() { return assetClass; }
    public String getMarket() { return market; }
    public String getCurrency() { return currency; }
    public boolean isTradable() { return isTradable; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
