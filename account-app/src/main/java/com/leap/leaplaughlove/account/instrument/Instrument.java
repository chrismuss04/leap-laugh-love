package com.leap.leaplaughlove.account.instrument;

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
     * Protected no-argument constructor for JPA.
     */
    protected Instrument() {
    }

    /**
     * Constructs a new Instrument with the specified details.
     * @param instrumentId the unique identifier of the instrument
     * @param symbol the trading symbol of the instrument
     * @param instrumentName the name of the instrument
     * @param assetClass the asset class of the instrument
     * @param market the market where the instrument is traded
     * @param currency the currency in which the instrument is traded
     * @param isTradable indicates whether the instrument is tradable
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

    /**
     * Returns the unique identifier of the instrument.
     * @return the instrument ID
     */
    public UUID getInstrumentId() { return instrumentId; }
    /**
     * Returns the trading symbol of the instrument.
     * @return the symbol
     */
    public String getSymbol() { return symbol; }
    /**
     * Returns the name of the instrument.
     * @return the instrument name
     */
    public String getInstrumentName() { return instrumentName; }
    /**
     * Returns the asset class of the instrument.
     * @return the asset class
     */
    public String getAssetClass() { return assetClass; }
    /**
     * Returns the market where the instrument is traded.
     * @return the market
     */
    public String getMarket() { return market; }
    /**
     * Returns the currency in which the instrument is traded.
     * @return the currency
     */
    public String getCurrency() { return currency; }
    /**
     * Checks if the instrument is tradable.
     * @return true if tradable, false otherwise
     */
    public boolean isTradable() { return isTradable; }
    /**
     * Returns the timestamp when the instrument was created.
     * @return the creation timestamp
     */
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

