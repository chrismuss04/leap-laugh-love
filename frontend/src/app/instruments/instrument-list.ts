import type { InstrumentOverviewRow } from '../services/instruments';

export type InstrumentSort = 'name' | 'price' | 'changePercent';
export type SortDirection = 'ascending' | 'descending';
export const UNCLASSIFIED = 'UNCLASSIFIED';

/** No asset filter keeps every row; instruments with no asset class count as UNCLASSIFIED. */
function matchesAsset(row: InstrumentOverviewRow, asset: string): boolean {
  if (!asset) {
    return true;
  }
  return (row.assetClass ?? UNCLASSIFIED) === asset;
}

/** An empty search keeps every row; otherwise the term must appear in the name or the symbol. */
function matchesSearch(row: InstrumentOverviewRow, term: string): boolean {
  if (!term) {
    return true;
  }
  return row.name.toLocaleLowerCase().includes(term) || row.symbol.toLocaleLowerCase().includes(term);
}

/** Filters and sorts a copy, keeping the API snapshot available for market-wide movers. */
export function instrumentList(rows: InstrumentOverviewRow[], query: string, asset: string,
    sort: InstrumentSort, direction: SortDirection): InstrumentOverviewRow[] {
  const term = query.trim().toLocaleLowerCase();
  return rows.filter(row => matchesAsset(row, asset) && matchesSearch(row, term))
    .sort((a, b) => {
      let order: number;
      if (sort === 'name') {
        order = a.name.localeCompare(b.name, 'en', { sensitivity: 'base' });
      } else {
        const left = a[sort];
        const right = b[sort];
        const leftMissing = left === null || !Number.isFinite(left);
        const rightMissing = right === null || !Number.isFinite(right);
        if (leftMissing !== rightMissing) {
          return leftMissing ? 1 : -1;
        }
        order = leftMissing ? 0 : left! - right!;
      }
      return (direction === 'ascending' ? order : -order) || a.symbol.localeCompare(b.symbol);
    });
}
