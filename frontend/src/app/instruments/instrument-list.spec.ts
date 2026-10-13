import { instrumentList } from './instrument-list';
import type { InstrumentOverviewRow } from '../services/instruments';
const row = (symbol: string, name: string, assetClass: string | null, price: number, changePercent: number | null): InstrumentOverviewRow => ({ symbol, name, assetClass, price, changePercent, currency: 'USD', asOf: '', baselinePrice: null, change: null });
const rows = [row('BBB', 'Beta', 'EQUITY', 100, -2), row('AAA', 'Alpha', 'CRYPTO', 9, 10), row('IDX', 'Index', null, 20, null), row('AAC', 'Alpha', 'EQUITY', 9, 10)];
const symbols = (items: InstrumentOverviewRow[]) => items.map(item => item.symbol);
describe('instrumentList', () => {
  it('combines trimmed case-insensitive search and asset filter', () => {
    expect(symbols(instrumentList(rows, ' ALPHA ', 'EQUITY', 'name', 'ascending'))).toEqual(['AAC']);
  });
  it('searches symbols and handles no matches', () => {
    expect(symbols(instrumentList(rows, 'bb', '', 'name', 'ascending'))).toEqual(['BBB']);
    expect(instrumentList(rows, 'missing', '', 'name', 'ascending')).toEqual([]);
  });
  it('filters unclassified instruments', () => {
    expect(symbols(instrumentList(rows, '', 'UNCLASSIFIED', 'name', 'ascending'))).toEqual(['IDX']);
  });
  it('sorts names both ways and breaks ties by symbol', () => {
    expect(symbols(instrumentList(rows, '', '', 'name', 'ascending'))).toEqual(['AAA', 'AAC', 'BBB', 'IDX']);
    expect(symbols(instrumentList(rows, '', '', 'name', 'descending'))).toEqual(['IDX', 'BBB', 'AAA', 'AAC']);
  });
  it('sorts prices numerically in both directions', () => {
    expect(symbols(instrumentList(rows, '', '', 'price', 'ascending'))).toEqual(['AAA', 'AAC', 'IDX', 'BBB']);
    expect(symbols(instrumentList(rows, '', '', 'price', 'descending'))).toEqual(['BBB', 'IDX', 'AAA', 'AAC']);
  });
  it('keeps null performance last in both directions', () => {
    expect(symbols(instrumentList(rows, '', '', 'changePercent', 'ascending'))).toEqual(['BBB', 'AAA', 'AAC', 'IDX']);
    expect(symbols(instrumentList(rows, '', '', 'changePercent', 'descending'))).toEqual(['AAA', 'AAC', 'BBB', 'IDX']);
  });
  it('does not mutate the original snapshot', () => {
    const original = rows.map(item => ({ ...item }));
    instrumentList(rows, '', '', 'price', 'descending');
    expect(rows).toEqual(original);
  });
  it('handles an empty universe', () => {
    expect(instrumentList([], '', '', 'name', 'ascending')).toEqual([]);
  });
});
