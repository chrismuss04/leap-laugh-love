import { liveInstruments, marketMovers, needsDailyRefresh } from './market-movers';
import type { InstrumentOverviewRow } from '../services/instruments';
const row = (symbol: string, changePercent: number | null): InstrumentOverviewRow => ({
 symbol, name: symbol, changePercent, assetClass: 'EQUITY', currency: 'USD', price: 100,
 baselinePrice: 100, change: 0, asOf: '2026-10-12T12:00:00Z'
});
const symbols = (rows: InstrumentOverviewRow[]) => rows.map(row => row.symbol);
describe('market movers and live prices', () => {
 it('ranks only positive gainers and negative losers', () => {
  const rows = [row('UP', 10), row('DOWN', -8), row('FLAT', 0), row('UNKNOWN', null), row('BAD', NaN)];
  expect(symbols(marketMovers(rows, 'gainers'))).toEqual(['UP']);
  expect(symbols(marketMovers(rows, 'losers'))).toEqual(['DOWN']);
 });
 it('limits each side to five ordered by percentage', () => {
  const rows = Array.from({ length: 7 }, (_, i) => row(String(i), i + 1));
  expect(symbols(marketMovers(rows, 'gainers'))).toEqual(['6', '5', '4', '3', '2']);
  expect(symbols(marketMovers(rows.map(r => ({ ...r, changePercent: -r.changePercent! })), 'losers'))).toEqual(['6', '5', '4', '3', '2']);
 });
 it('breaks ties by symbol without mutating source data', () => {
  const rows = [row('B', 5), row('A', 5)];
  expect(symbols(marketMovers(rows, 'gainers'))).toEqual(['A', 'B']);
  expect(symbols(rows)).toEqual(['B', 'A']);
 });
 it('recomputes performance using the selected baseline', () => {
  const rows = liveInstruments([row('A', 0)], { A: 125 }, {});
  expect(rows[0].price).toEqual(125);
  expect(rows[0].change).toEqual(25);
  expect(rows[0].changePercent).toEqual(25);
 });
 it('does not fabricate performance without history', () => {
  const rows = liveInstruments([{ ...row('A', null), baselinePrice: null }], { A: 125 }, {});
  expect(rows[0].change).toEqual(null);
  expect(rows[0].changePercent).toEqual(null);
 });
 it('retains snapshot for invalid or pre-snapshot cached prices', () => {
  const rows = [row('A', 0)];
  for (const price of [NaN, -1, 0, 99]) {
   expect(liveInstruments(rows, { A: price }, { A: 99 })).toEqual(rows);
  }
  expect(liveInstruments(rows, {}, {})).toEqual(rows);
 });
 it('refreshes across UTC day rollover but not within a day', () => {
  expect(needsDailyRefresh('2026-10-12T12:00:00Z', new Date('2026-10-12T23:59:59Z'))).toEqual(false);
  expect(needsDailyRefresh('2026-10-12T12:00:00Z', new Date('2026-10-13T00:00:00Z'))).toEqual(true);
 });
 it('returns empty rankings for no qualifying instruments', () => {
  expect(marketMovers([], 'gainers')).toEqual([]);
  expect(marketMovers([row('A', null)], 'losers')).toEqual([]);
 });
});
