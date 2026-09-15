package com.leap.leaplaughlove.marketdata.simulation;

/**
 * Published by {@link MarketSimulationEngine} on every simulation tick, so other components
 * can react to newly simulated prices.
 * @param priceState the snapshot of the newly simulated price
 */
public record PriceTickEvent(PriceState priceState) {
}
