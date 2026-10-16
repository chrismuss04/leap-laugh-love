import { RecapPayload } from '../app/services/recap';

/** A full September recap; tests override what they need. */
export function recapPayload(overrides: Partial<RecapPayload> = {}): RecapPayload {
  return {
    month: '2026-09',
    quiet: false,
    moneyMoved: { total: 71169.2, perMarketDay: 3234.96, marketDays: 22, filledOrders: 41, accounts: 3 },
    topTickers: [
      { symbol: 'NVDA', tradedValue: 18240, filledOrders: 9, closes: [100, 104, 110] },
      { symbol: 'AAPL', tradedValue: 12905, filledOrders: 6, closes: [] }
    ],
    topGainer: { symbol: 'NVDA', returnPct: 18.42, pnl: 1840, closes: [100, 104, 110] },
    topGainers: [{ symbol: 'NVDA', returnPct: 18.42, pnl: 1840 }],
    biggestLosers: [{ symbol: 'SBUX', returnPct: -12.3, pnl: -410 }],
    tradingReturn: { returnPct: 6.8, pnl: 2940.4, dailyPnl: [0, 120, -40, 2940.4] },
    benchmark: { symbol: 'SPX', name: 'S&P 500', returnPct: 2.1, differencePts: 4.7 },
    instrumentsTraded: 23,
    topSector: { sector: 'Information Technology', label: 'Tech', tradedValue: 40000 },
    topPercent: 4,
    ...overrides
  };
}
