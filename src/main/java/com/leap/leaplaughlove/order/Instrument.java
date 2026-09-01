package com.leap.leaplaughlove.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "instruments", schema = "trading")
public class Instrument {

    @Id
    @GeneratedValue
    @Column(name = "instrument_id")
    private UUID instrumentId;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "instrument_name", nullable = false)
    private String instrumentName;

    @Column(name = "asset_class", nullable = false)
    private String assetClass;

    @Column(name = "market", nullable = false)
    private String market;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "is_tradable", nullable = false)
    private boolean tradable;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public UUID getInstrumentId() {
        return instrumentId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getInstrumentName() {
        return instrumentName;
    }

    public void setInstrumentName(String instrumentName) {
        this.instrumentName = instrumentName;
    }

    public String getAssetClass() {
        return assetClass;
    }

    public void setAssetClass(String assetClass) {
        this.assetClass = assetClass;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public boolean isTradable() {
        return tradable;
    }

    public void setTradable(boolean tradable) {
        this.tradable = tradable;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
