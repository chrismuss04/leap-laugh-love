import type { InstrumentOverviewRow } from '../services/instruments';

export type InstrumentSort = 'name' | 'price' | 'changePercent';
export type SortDirection = 'ascending' | 'descending';
export const UNCLASSIFIED = 'UNCLASSIFIED';

/** Filters and sorts a copy, keeping the API snapshot available for market-wide movers. */
export function instrumentList(rows: InstrumentOverviewRow[], query: string, asset: string,
    sort: InstrumentSort, direction: SortDirection): InstrumentOverviewRow[] {
  const term = query.trim().toLocaleLowerCase();
  return rows.filter(row => (!asset || (row.assetClass ?? UNCLASSIFIED) === asset)
    && (!term || row.name.toLocaleLowerCase().includes(term) || row.symbol.toLocaleLowerCase().includes(term)))
    .sort((a, b) => {
      let order: number;
      if (sort === 'name') {
        order = a.name.localeCompare(b.name, 'en', { sensitivity: 'base' });
      } else {
        const left = a[sort];
        const right = b[sort];
        const leftMissing = left === null || !Number.isFinite(left);
        const rightMissing = right === null || !Number.isFinite(right);
        if (leftMissing !== rightMissing) return leftMissing ? 1 : -1;
        order = leftMissing ? 0 : left! - right!;
      }
      return (direction === 'ascending' ? order : -order) || a.symbol.localeCompare(b.symbol);
    });
}
