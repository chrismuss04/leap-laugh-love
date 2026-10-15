package com.leap.leaplaughlove.marketdata.simulation;

import com.leap.leaplaughlove.common.market.MarketSession;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * In-memory snapshot of the latest simulated price for an instrument, and the payload
 * published on {@link PriceTickEvent} and streamed to clients.
 * @param symbol the instrument symbol
 * @param price the latest simulated price
 * @param asOf the timestamp the price was generated
 * @param session the market session the price was made in: quotes are wider in extended hours,
 *                and a CLOSED price is the frozen last price, not a trade
 */
public record PriceState(String symbol, BigDecimal price, OffsetDateTime asOf, MarketSession session) {

    /**
     * Normalises a missing session to REGULAR.
     */
    public PriceState {
        session = session != null ? session : MarketSession.REGULAR;
    }

    /**
     * A price made during regular trading.
     * @param symbol the instrument symbol
     * @param price the simulated price
     * @param asOf the timestamp the price was generated
     */
    public PriceState(String symbol, BigDecimal price, OffsetDateTime asOf) {
        this(symbol, price, asOf, MarketSession.REGULAR);
    }
}
