/** One holding, combined across the client's accounts and priced live. */
export interface HoldingView {
  symbol: string;
  name: string;
  quantity: number;
  averageCost: number;
  price: number | null;
  previousClose: number | null;
  marketValue: number | null;
  dayChange: number | null;
  dayChangePercent: number | null;
  totalReturn: number | null;
  totalReturnPercent: number | null;
  intraday: number[];
}

/** What the trade panel needs to know about one account. */
export interface TradeAccount {
  accountId: string;
  accountNumber: string;
  buyingPower: number;
  /** Shares held per symbol in this account. */
  shares: Record<string, number>;
}

export interface MarketIndex {
  symbol: string;
  label: string;
  price: number | null;
  previousClose: number | null;
}

/** Headline indices for the ticker strip; all are simulated instruments in market-data-app. */
export const MARKET_INDICES: { symbol: string; label: string }[] = [
  { symbol: 'SPX', label: 'S&P 500' },
  { symbol: 'NDX', label: 'Nasdaq 100' },
  { symbol: 'DJI', label: 'Dow 30' },
  { symbol: 'RUT', label: 'Russell 2000' },
  { symbol: 'VIX', label: 'VIX' },
  { symbol: 'BTCUSD', label: 'Bitcoin' }
];
