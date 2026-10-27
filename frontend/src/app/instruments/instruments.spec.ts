import { signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { InstrumentsComponent } from './instruments';
import { InstrumentOverview, InstrumentOverviewRow } from '../services/instruments';
import { PriceStreamService } from '../services/price-stream';

const row = (symbol: string, change: number, assetClass = 'EQUITY'): InstrumentOverviewRow => ({
 symbol, name: symbol === 'AAPL' ? 'Apple Inc.' : symbol, assetClass, currency: 'USD',
 price: 100 + change, baselinePrice: 100, change, changePercent: change, asOf: '2026-10-12T12:00:00Z'
});
const overview = (): InstrumentOverview => ({ period: 'today', periodStart: '2026-10-12T00:00:00Z',
 asOf: new Date().toISOString(), instruments: [row('AAPL', 5), row('BTCUSD', -10, 'CRYPTO')],
 total: 2, page: 0, size: 10, gainers: [row('AAPL', 5)], losers: [row('BTCUSD', -10, 'CRYPTO')] });

describe('Instruments page integration', () => {
 let fixture: ComponentFixture<InstrumentsComponent>;
 let http: HttpTestingController;
 let stream: { prices: ReturnType<typeof signal<Record<string, number>>>; status: ReturnType<typeof signal<string>>; watch: ReturnType<typeof vi.fn>; release: ReturnType<typeof vi.fn> };
  const url = '/api/marketdata/instruments';
 const render = () => fixture.detectChanges();
 const text = () => fixture.nativeElement.textContent as string;
 const load = (data = overview()) => { http.match(request => request.url === url)[0].flush(data); render(); };
 beforeEach(() => {
  stream = { prices: signal<Record<string, number>>({}), status: signal('live'), watch: vi.fn(), release: vi.fn() };
  TestBed.configureTestingModule({ imports: [InstrumentsComponent], providers: [provideHttpClient(),
   provideHttpClientTesting(), { provide: PriceStreamService, useValue: stream }] });
  http = TestBed.inject(HttpTestingController);
  fixture = TestBed.createComponent(InstrumentsComponent);
  render();
 });
 afterEach(() => { fixture.destroy(); http.verify(); });
 it('renders ten rows per page with a partial last page and disabled boundaries', () => {
  load({ ...overview(), instruments: Array.from({ length: 10 }, (_, i) => row(`S${String(i).padStart(2, '0')}`, i)), total: 23 });
  const buttons = () => fixture.nativeElement.querySelectorAll('.pagination button') as NodeListOf<HTMLButtonElement>;
  const count = () => fixture.nativeElement.querySelectorAll('tbody tr:not(:last-child)').length;
  expect(count()).toBe(10);
  expect(buttons()[0].disabled).toBe(true);
  buttons()[1].click(); render();
  http.expectOne(request => request.url === url && request.params.get('page') === '1').flush({ ...overview(), instruments: Array.from({ length: 10 }, (_, i) => row(`S${String(i + 10).padStart(2, '0')}`, i + 10)), total: 23, page: 1 }); render();
  expect(count()).toBe(10);
  expect(fixture.componentInstance.pagedInstruments()[0].symbol).toBe('S10');
  buttons()[1].click(); render();
  http.expectOne(request => request.url === url && request.params.get('page') === '2').flush({ ...overview(), instruments: Array.from({ length: 3 }, (_, i) => row(`S${String(i + 20).padStart(2, '0')}`, i + 20)), total: 23, page: 2 }); render();
  expect(count()).toBe(3);
  expect(text()).toContain('Showing 21–23 of 23');
  expect(buttons()[1].disabled).toBe(true);
  buttons()[0].click(); render();
  expect(fixture.componentInstance.page()).toBe(2);
 });
 it('resets pagination on search, filter, sorting and period changes', () => {
  load({ ...overview(), instruments: Array.from({ length: 10 }, (_, i) => row(`S${i}`, i)), total: 21 });
  const component = fixture.componentInstance;
  component.nextPage(); component.query.set('S1'); render();
  expect(component.page()).toBe(1);
  component.clearFilters(); component.nextPage(); component.asset.set('EQUITY'); render();
  expect(component.page()).toBe(1);
  component.nextPage(); component.sortBy('price'); render();
  expect(component.page()).toBe(1);
  component.nextPage(); component.selectPeriod('week');
  http.expectOne(request => request.url === url && request.params.get('period') === 'week').flush({ ...overview(), period: 'week' }); render();
  expect(component.page()).toBe(1);
 });
 it('paginates after sorting and filtering while keeping movers market-wide', () => {
  load({ ...overview(), instruments: Array.from({ length: 10 }, (_, i) => row(`S${i}`, i)), total: 21 });
  const component = fixture.componentInstance;
  component.sortBy('price'); component.sortBy('price'); render();
  http.expectOne(request => request.url === url && request.params.get('sort') === 'price' && request.params.get('direction') === 'descending').flush({ ...overview(), instruments: Array.from({ length: 10 }, (_, i) => row(`S${20-i}`, 20-i)), total: 21 }); render();
  expect(component.pagedInstruments()[0].price).toBe(120);
  component.nextPage(); render();
  http.expectOne(request => request.url === url && request.params.get('page') === '1').flush({ ...overview(), instruments: Array.from({ length: 10 }, (_, i) => row(`S${10-i}`, 10-i)), total: 21, page: 1 }); render();
  expect(component.pagedInstruments()[0].price).toBe(110);
  expect(component.gainers()[0].price).toBe(120);
  component.query.set('missing'); render();
  expect(component.pagedInstruments()).toEqual([]);
 });
 it('loads today and displays instrument data and market-wide movers', () => {
  expect(text()).toContain('Loading instruments');
  load();
  expect(text()).toContain('Apple Inc.');
  expect(text()).toContain('+5.00%');
  expect(fixture.componentInstance.gainers().map(r => r.symbol)).toEqual(['AAPL']);
  expect(fixture.componentInstance.losers().map(r => r.symbol)).toEqual(['BTCUSD']);
  expect(stream.watch).toHaveBeenCalledWith(new Set(['AAPL', 'BTCUSD']), 'instruments');
 });
 it('combines actual search and asset controls without filtering movers', () => {
  load();
  const input = fixture.nativeElement.querySelector('input[type=search]') as HTMLInputElement;
  input.value = ' apple '; input.dispatchEvent(new Event('input'));
  render();
  http.expectOne(request => request.url === url && request.params.get('query') === ' apple ').flush({ ...overview(), instruments: [row('AAPL', 5)], total: 1 }); render();
  expect(fixture.componentInstance.visibleInstruments().map(r => r.symbol)).toEqual(['AAPL']);
  const select = fixture.nativeElement.querySelector('.list-controls select') as HTMLSelectElement;
  select.value = 'CRYPTO'; select.dispatchEvent(new Event('change')); render();
  http.expectOne(request => request.url === url && request.params.get('asset') === 'CRYPTO').flush({ ...overview(), instruments: [], total: 0 }); render();
  expect(text()).toContain('No instruments match');
  expect(fixture.componentInstance.losers().length).toBe(1);
  (fixture.nativeElement.querySelector('.clear-filters') as HTMLButtonElement).click(); render();
  http.expectOne(request => request.url === url && request.params.get('asset') === '').flush(overview()); render();
  expect(fixture.componentInstance.visibleInstruments().length).toBe(2);
 });
 it('toggles header sort and exposes aria-sort', () => {
  load();
  const button = fixture.nativeElement.querySelector('thead th.numeric button') as HTMLButtonElement;
  button.click(); render();
  expect(fixture.componentInstance.visibleInstruments()[0].symbol).toBe('BTCUSD');
  expect(button.parentElement?.getAttribute('aria-sort')).toBe('ascending');
  button.click(); render();
  expect(fixture.componentInstance.visibleInstruments()[0].symbol).toBe('AAPL');
  expect(button.parentElement?.getAttribute('aria-sort')).toBe('descending');
 });
 it('cancels stale period requests and renders the selected period', () => {
  const initial = http.expectOne(request => request.url === url && request.params.get('period') === 'today');
  fixture.componentInstance.selectPeriod('week');
  expect(initial.cancelled).toBe(true);
  const week = http.expectOne(request => request.url === url && request.params.get('period') === 'week');
  fixture.componentInstance.selectPeriod('month');
  expect(week.cancelled).toBe(true);
  http.expectOne(request => request.url === url && request.params.get('period') === 'month').flush({ ...overview(), period: 'month' }); render();
  expect(fixture.componentInstance.overview()?.period).toBe('month');
 });
 it('recovers after request failure through the retry button', () => {
  http.expectOne(request => request.url === url && request.params.get('period') === 'today').flush({}, { status: 503, statusText: 'Unavailable' }); render();
  expect(text()).toContain('Could not load instruments');
  (fixture.nativeElement.querySelector('.banner button') as HTMLButtonElement).click();
  http.expectOne(request => request.url === url && request.params.get('period') === 'today').flush(overview()); render();
  expect(fixture.nativeElement.querySelector('.banner')).toBeNull();
  expect(text()).toContain('Apple Inc.');
 });
 it('distinguishes an empty universe from search results', () => {
  load({ ...overview(), instruments: [] });
  expect(text()).toContain('No instruments match');
  expect(text()).toContain('No gainers');
 });
 it('updates rankings live and retains prices during disconnection', () => {
  load(); stream.prices.set({ AAPL: 80 }); render();
  expect(fixture.componentInstance.losers()[0].symbol).toBe('AAPL');
  expect(fixture.componentInstance.instruments()[0].changePercent).toBe(-20);
  stream.status.set('reconnecting'); render();
  expect(fixture.componentInstance.instruments()[0].price).toBe(80);
  expect(text()).toContain('Showing the last available prices');
 });
 it('ignores pre-snapshot prices but accepts later reversions', () => {
  stream.prices.set({ AAPL: 99 }); load();
  expect(fixture.componentInstance.instruments()[0].price).toBe(105);
  stream.prices.set({ AAPL: 110 }); render();
  expect(fixture.componentInstance.instruments()[0].price).toBe(110);
  stream.prices.set({ AAPL: 99 }); render();
  expect(fixture.componentInstance.instruments()[0].price).toBe(99);
 });
 it('refreshes old UTC baselines and stops the timer on destroy', fakeAsync(() => {
  http.expectOne(request => request.url === url && request.params.get('period') === 'today');
  fixture.destroy();
  fixture = TestBed.createComponent(InstrumentsComponent);
  render();
  load({ ...overview(), asOf: '2000-01-01T00:00:00Z' });
  tick(30_000);
  http.expectOne(request => request.url === url && request.params.get('period') === 'today').flush(overview());
  fixture.destroy(); tick(60_000);
  http.expectNone(request => request.url === url && request.params.get('period') === 'today');
 }));
 it('cancels in-flight requests when destroyed', () => {
  const request = http.expectOne(req => req.url === url && req.params.get('period') === 'today'); fixture.destroy();
  expect(request.cancelled).toBe(true);
 });
});
