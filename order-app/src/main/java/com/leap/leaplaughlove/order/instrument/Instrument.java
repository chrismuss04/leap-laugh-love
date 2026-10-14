package com.leap.leaplaughlove.order.instrument;

import com.leap.leaplaughlove.common.persistence.BaseInstrument;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Represents a financial instrument in the trading system (trading.instruments).
 */
@Entity
@Table(name = "instruments", schema = "trading")
public class Instrument extends BaseInstrument {

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
        super(instrumentId, symbol, instrumentName, assetClass, market, currency, isTradable);
    }
}
