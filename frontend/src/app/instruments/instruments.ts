import { Component, computed, effect, inject, linkedSignal, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { BehaviorSubject, catchError, EMPTY, interval, switchMap, tap } from 'rxjs';
import { InstrumentOverview, InstrumentPeriod, InstrumentsService } from '../services/instruments';
import { direction, formatNumber, formatSignedPercent } from '../shared/format';
import { instrumentList, InstrumentSort, SortDirection, UNCLASSIFIED } from './instrument-list';
import { PriceStreamService } from '../services/price-stream';
import { liveInstruments, marketMovers, needsDailyRefresh } from './market-movers';

@Component({
  selector: 'app-instruments',
  imports: [CommonModule],
  templateUrl: './instruments.html',
  styleUrl: './instruments.css'
})
export class InstrumentsComponent {
  readonly period = signal<InstrumentPeriod>('today');
  readonly overview = signal<InstrumentOverview | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly stream = inject(PriceStreamService);
  private readonly initialPrices = signal<Record<string, number>>({});
  readonly instruments = computed(() => liveInstruments(this.overview()?.instruments ?? [],
    this.stream.prices(), this.initialPrices()));
  readonly gainers = computed(() => marketMovers(this.instruments(), 'gainers'));
  readonly losers = computed(() => marketMovers(this.instruments(), 'losers'));
  readonly query = signal('');
  readonly asset = signal('');
  readonly sort = signal<InstrumentSort>('name');
  readonly sortDirection = signal<SortDirection>('ascending');
  readonly assetOptions = computed(() => [...new Set(
    (this.overview()?.instruments ?? []).map(row => row.assetClass ?? UNCLASSIFIED)
  )].sort());
  readonly visibleInstruments = computed(() => instrumentList(this.instruments(),
    this.query(), this.asset(), this.sort(), this.sortDirection()));
  readonly pageSize = 10;
  private readonly requestedPage = linkedSignal({
    source: () => [this.query(), this.asset(), this.sort(), this.sortDirection(), this.period()],
    computation: () => 1
  });
  readonly pageCount = computed(() => Math.max(1, Math.ceil(this.visibleInstruments().length / this.pageSize)));
  readonly page = computed(() => Math.min(this.requestedPage(), this.pageCount()));
  readonly pageStart = computed(() => this.visibleInstruments().length ? (this.page() - 1) * this.pageSize + 1 : 0);
  readonly pageEnd = computed(() => Math.min(this.page() * this.pageSize, this.visibleInstruments().length));
  readonly pagedInstruments = computed(() => this.visibleInstruments().slice(
    (this.page() - 1) * this.pageSize, this.page() * this.pageSize));
  readonly direction = direction;
  readonly number = formatNumber;
  readonly percent = formatSignedPercent;
  private readonly service = inject(InstrumentsService);
  private readonly requests = new BehaviorSubject<InstrumentPeriod>('today');

  constructor() {
    // Once a symbol receives a changed tick, allow all later ticks, including
    // a return to the price cached before the overview was fetched.
    effect(() => {
      const prices = this.stream.prices();
      const initial = this.initialPrices();
      const remaining = { ...initial };
      let changed = false;
      for (const symbol of Object.keys(initial)) {
        if (Number.isFinite(prices[symbol]) && prices[symbol] > 0 && prices[symbol] !== initial[symbol]) {
          delete remaining[symbol];
          changed = true;
        }
      }
      if (changed) this.initialPrices.set(remaining);
    });
    this.requests.pipe(
      tap(() => {
        this.loading.set(true);
        this.error.set(null);
        this.overview.set(null);
      }),
      // Cancel stale requests when the user changes periods or retries.
      switchMap(period => this.service.getOverview(period).pipe(
        catchError(() => {
          this.error.set('Could not load instruments. Please try again.');
          this.loading.set(false);
          return EMPTY;
        })
      )),
      takeUntilDestroyed()
    ).subscribe(response => {
      this.initialPrices.set({ ...this.stream.prices() });
      this.overview.set(response);
      this.stream.watch(response.instruments.map(row => row.symbol));
      this.loading.set(false);
    });
    // RxJS tears down this timer with the component; the shared stream stays with the shell.
    interval(30_000).pipe(takeUntilDestroyed()).subscribe(() => {
      const snapshot = this.overview();
      if (!this.loading() && snapshot && needsDailyRefresh(snapshot.asOf, new Date())) {
        this.requests.next(this.period());
      }
    });
  }

  selectPeriod(value: string): void {
    if (value !== 'today' && value !== 'week' && value !== 'month') return;
    this.period.set(value);
    this.requests.next(value);
  }

  retry(): void { this.requests.next(this.period()); }

  previousPage(): void { this.requestedPage.set(Math.max(1, this.page() - 1)); }
  nextPage(): void { this.requestedPage.set(Math.min(this.pageCount(), this.page() + 1)); }

  sortBy(column: InstrumentSort): void {
    this.sortDirection.set(this.sort() === column && this.sortDirection() === 'ascending'
      ? 'descending' : 'ascending');
    this.sort.set(column);
  }

  sortState(column: InstrumentSort): 'none' | SortDirection {
    return this.sort() === column ? this.sortDirection() : 'none';
  }

  sortIndicator(column: InstrumentSort): string {
    return this.sort() === column ? (this.sortDirection() === 'ascending' ? '\u2191' : '\u2193') : '\u2195';
  }

  clearFilters(): void { this.query.set(''); this.asset.set(''); }

  signed(value: number | null): string {
    if (value === null) return formatNumber(null);
    return `${value > 0 ? '+' : value < 0 ? '\u2212' : ''}${formatNumber(Math.abs(value))}`;
  }

  assetLabel(value: string | null): string {
    return ({ EQUITY: 'Equity', FX: 'FX', CRYPTO: 'Crypto', UNCLASSIFIED: 'Unclassified' } as Record<string, string>)[value ?? '']
      ?? value ?? 'Unclassified';
  }
}
