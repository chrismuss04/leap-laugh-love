import { Component, DestroyRef, computed, effect, inject, linkedSignal, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { BehaviorSubject, catchError, EMPTY, interval, switchMap, tap } from 'rxjs';
import { InstrumentOverview, InstrumentPeriod, InstrumentsService } from '../services/instruments';
import { direction, formatNumber, formatSignedPercent } from '../shared/format';
import { InstrumentSort, SortDirection, UNCLASSIFIED } from './instrument-list';
import { PriceStreamService } from '../services/price-stream';
import { liveInstruments, needsDailyRefresh } from './market-movers';

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
  private readonly moverRows = signal<{ gainers: InstrumentOverview['instruments']; losers: InstrumentOverview['instruments'] }>({ gainers: [], losers: [] });
  readonly instruments = computed(() => liveInstruments(this.overview()?.instruments ?? [],
    this.stream.prices(), this.initialPrices()));
  readonly gainers = computed(() => liveInstruments(this.moverRows().gainers, this.stream.prices(), {}));
  readonly losers = computed(() => liveInstruments(this.moverRows().losers, this.stream.prices(), {}));
  readonly query = signal('');
  readonly asset = signal('');
  readonly sort = signal<InstrumentSort>('name');
  readonly sortDirection = signal<SortDirection>('ascending');
  readonly assetOptions = computed(() => ['CRYPTO', 'EQUITY', 'FX', UNCLASSIFIED]);
  readonly visibleInstruments = computed(() => this.instruments());
  readonly pageSize = 10;
  private readonly requestedPage = linkedSignal({
    source: () => [this.query(), this.asset(), this.sort(), this.sortDirection(), this.period()],
    computation: () => 0
  });
  private readonly maxPageIndex = signal(0);
  private readonly requestKey = computed(() => [this.period(), this.requestedPage(), this.query(), this.asset(),
    this.sort(), this.sortDirection()].join('|'));
  readonly pageCount = computed(() => Math.max(1, Math.ceil((this.overview()?.total ?? 0) / this.pageSize)));
  readonly page = computed(() => this.requestedPage() + 1);
  readonly pageStart = computed(() => (this.overview()?.total ?? 0) ? (this.page() - 1) * this.pageSize + 1 : 0);
  readonly pageEnd = computed(() => Math.min(this.page() * this.pageSize, this.overview()?.total ?? 0));
  readonly pagedInstruments = computed(() => this.instruments());
  readonly direction = direction;
  readonly number = formatNumber;
  readonly percent = formatSignedPercent;
  private readonly service = inject(InstrumentsService);
  private readonly requests = new BehaviorSubject<void>(undefined);
  private lastRequestKey = '';
  /** Owns this page's symbols on the shared stream, so they stop streaming when it closes. */
  private static readonly STREAM_OWNER = 'instruments';

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
      if (changed) { this.initialPrices.set(remaining); }
    });
    this.requests.pipe(
      tap(() => {
        this.loading.set(true);
        this.error.set(null);
      }),
      // Cancel stale requests when the user changes periods or retries.
      switchMap(() => this.service.getPage(this.period(), this.requestedPage(), this.pageSize,
        this.query(), this.asset(), this.sort(), this.sortDirection()).pipe(
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
      this.maxPageIndex.set(Math.max(0, Math.ceil(response.total / this.pageSize) - 1));
      if (this.requestedPage() > this.maxPageIndex()) this.requestedPage.set(this.maxPageIndex());
      this.moverRows.set({ gainers: response.gainers, losers: response.losers });
      const symbols = new Set([...response.instruments, ...response.gainers, ...response.losers]
        .map(row => row.symbol));
      this.stream.release(InstrumentsComponent.STREAM_OWNER);
      this.stream.watch(symbols, InstrumentsComponent.STREAM_OWNER);
      this.loading.set(false);
    });
    // Every instrument is hundreds of symbols a second; stop streaming the ones no other page
    // watches once this one closes.
    inject(DestroyRef).onDestroy(() => this.stream.release(InstrumentsComponent.STREAM_OWNER));
    // RxJS tears down this timer with the component; the shared stream stays with the shell.
    interval(30_000).pipe(takeUntilDestroyed()).subscribe(() => {
      const snapshot = this.overview();
      if (!this.loading() && snapshot && needsDailyRefresh(snapshot.asOf, new Date())) {
        if (this.lastRequestKey !== '') { this.requests.next(); }
      }
    });
    effect(() => {
      const request = this.requestKey();
      if (this.lastRequestKey !== '' && request !== this.lastRequestKey && !this.loading()) {
        this.lastRequestKey = request;
        this.requests.next();
      } else if (this.lastRequestKey === '') {
        this.lastRequestKey = request;
      }
    });
    effect(() => {
      if (!this.loading()) this.maxPageIndex.set(Math.max(0, this.pageCount() - 1));
    });
    effect(() => {
      const request = this.requestKey();
      if (this.lastRequestKey !== '' && request !== this.lastRequestKey && this.loading()) {
        this.lastRequestKey = request;
      }
    });
  }

  selectPeriod(value: string): void {
    if (value !== 'today' && value !== 'week' && value !== 'month') {
      return;
    }
    this.period.set(value);
  }

  retry(): void { this.requests.next(); }

  previousPage(): void { this.requestedPage.set(Math.max(0, this.requestedPage() - 1)); }
  nextPage(): void { this.requestedPage.set(Math.min(this.maxPageIndex(), this.requestedPage() + 1)); }

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
    if (value === null) {
      return formatNumber(null);
    }
    return `${value > 0 ? '+' : value < 0 ? '\u2212' : ''}${formatNumber(Math.abs(value))}`;
  }

  assetLabel(value: string | null): string {
    return ({ EQUITY: 'Equity', FX: 'FX', CRYPTO: 'Crypto', UNCLASSIFIED: 'Unclassified' } as Record<string, string>)[value ?? '']
      ?? value ?? 'Unclassified';
  }
}
