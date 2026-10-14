import type { InstrumentOverviewRow } from '../services/instruments';

/** Ignore cached stream values from before this snapshot until a fresh price arrives. */
export function liveInstruments(rows: InstrumentOverviewRow[], prices: Record<string, number>,
    initialPrices: Record<string, number>): InstrumentOverviewRow[] {
  return rows.map(row => {
    const price = prices[row.symbol];
    if (!Number.isFinite(price) || price <= 0 || price === initialPrices[row.symbol]) { return row; }
    const baseline = row.baselinePrice;
    const change = baseline !== null && baseline > 0 ? price - baseline : null;
    return { ...row, price, change, changePercent: change === null ? null : change / baseline! * 100 };
  });
}

/** Rankings are market-wide, excluding flat prices and unavailable performance. */
export function marketMovers(rows: InstrumentOverviewRow[], side: 'gainers' | 'losers'): InstrumentOverviewRow[] {
  return rows.filter(row => row.changePercent !== null && Number.isFinite(row.changePercent)
    && (side === 'gainers' ? row.changePercent > 0 : row.changePercent < 0))
    .sort((a, b) => (side === 'gainers'
      ? b.changePercent! - a.changePercent! : a.changePercent! - b.changePercent!)
      || a.symbol.localeCompare(b.symbol)).slice(0, 5);
}

export function needsDailyRefresh(snapshotAsOf: string, now: Date): boolean {
  return snapshotAsOf.slice(0, 10) !== now.toISOString().slice(0, 10);
}
