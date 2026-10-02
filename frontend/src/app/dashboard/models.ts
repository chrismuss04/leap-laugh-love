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
  /** The account's saved price protection in percent, or null when off. */
  maxSlippagePercent: number | null;
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

/** One account, valued live, for the all-accounts overview and the account switcher. */
export interface AccountView {
  accountId: string;
  accountNumber: string;
  /** "Brokerage ··4821" - the API has no account nicknames yet. */
  name: string;
  /** Last four of the account number, for compact labels. */
  mask: string;
  /** Stable per-account accent, used in the allocation bar and switcher. */
  color: string;
  status: string | null;
  tradingEnabled: boolean | null;
  openedAt: string | null;
  /** When the account became empty, if the nightly job flagged it inactive. */
  inactiveSince: string | null;
  cash: number;
  marketValue: number;
  value: number;
  dayChange: number | null;
  dayChangePercent: number | null;
  totalReturn: number | null;
  positionCount: number;
  /** Fraction (0-1) of the client's combined value held in this account. */
  share: number;
  /** Today's account value at each intraday bucket, for sparklines and the account chart. */
  intraday: number[];
  /** Value at the previous close: yesterday's prices for today's shares, plus cash. */
  previousCloseValue: number | null;
}

/**
 * Account accents: distinct from each other and from the gain/loss colours, so an account's
 * colour never reads as a direction.
 */
export const ACCOUNT_COLORS = ['#5b9dff', '#a78bfa', '#2dd4bf', '#f5b041', '#f472b6', '#94a3b8'];

export function accountMask(accountNumber: string): string {
  return accountNumber.slice(-4);
}

export function accountName(accountNumber: string): string {
  return `Brokerage ··${accountMask(accountNumber)}`;
}
