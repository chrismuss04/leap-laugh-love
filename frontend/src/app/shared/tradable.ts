/**
 * Symbols market data prices but order-app won't trade: the indices and Bitcoin, which have no row
 * in trading.instruments, so TradeValidationService rejects orders for them. The frontend has no
 * API for tradability yet, so this list mirrors the seeded instruments; order-app still rejects
 * anything else that isn't tradable.
 */
const NOT_TRADABLE = new Set(['SPX', 'NDX', 'DJI', 'RUT', 'VIX', 'US10Y', 'BTCUSD']);

export function isTradable(symbol: string): boolean {
  return !NOT_TRADABLE.has(symbol.toUpperCase());
}
