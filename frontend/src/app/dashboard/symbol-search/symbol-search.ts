import { Component, ElementRef, EventEmitter, HostListener, Input, Output, ViewChild, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LatestPrice, MarketDataService } from '../../services/market-data';
import { formatMoney } from '../../shared/format';

interface SearchResult {
  symbol: string;
  name: string | null;
  price: number;
  /** Why it can't be picked here, or null when it can. */
  reason: string | null;
}

/**
 * Symbol search across every simulated instrument. "/" focuses it from anywhere on the page;
 * arrow keys and Enter pick a result, which opens it in the trade panel. A page can mark some
 * results unavailable (with the reason shown beside them), and they can't be picked.
 */
@Component({
    selector: 'app-symbol-search',
    imports: [CommonModule, FormsModule],
    templateUrl: './symbol-search.html',
    styleUrl: './symbol-search.css'
})
export class SymbolSearchComponent {
  /** Display name per symbol, searched alongside the ticker and shown under it. */
  @Input() names: Record<string, string> = {};
  /** Live prices, preferred over the snapshot loaded for search. */
  @Input() livePrices: Record<string, number> = {};
  /** Why a symbol can't be picked here ("Not tradable"), or null when it can. */
  @Input() unavailable: (symbol: string) => string | null = () => null;
  @Input() placeholder = 'Search symbols to trade';
  /** Whether "/" focuses this search from anywhere on the page. */
  @Input() shortcut = true;
  @Output() pick = new EventEmitter<string>();
  /** The same pick with the instrument's name, for pages that show it. */
  @Output() picked = new EventEmitter<{ symbol: string; name: string | null }>();

  @ViewChild('input') input?: ElementRef<HTMLInputElement>;

  query = '';
  readonly open = signal(false);
  readonly highlighted = signal(0);
  results: SearchResult[] = [];
  loading = false;

  money = formatMoney;

  private readonly marketData = inject(MarketDataService);
  private catalog: LatestPrice[] = [];

  @HostListener('document:keydown', ['$event'])
  onGlobalKey(event: KeyboardEvent): void {
    const target = event.target as HTMLElement | null;
    const typing = target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName));
    if (event.key === '/' && !typing && this.shortcut) {
      event.preventDefault();
      this.input?.nativeElement.focus();
    }
  }

  onFocus(): void {
    this.open.set(true);
    if (this.catalog.length === 0 && !this.loading) {
      this.loading = true;
      this.marketData.getAllLatestPrices().subscribe(prices => {
        this.catalog = [...prices].sort((a, b) => a.symbol.localeCompare(b.symbol));
        this.loading = false;
        this.filter();
      });
    } else {
      this.filter();
    }
  }

  filter(): void {
    const q = this.query.trim().toUpperCase();
    if (!q) {
      this.results = [];
      this.highlighted.set(0);
      return;
    }
    const matches = this.catalog.filter(item =>
      item.symbol.includes(q) || (this.names[item.symbol] ?? '').toUpperCase().includes(q));
    // Exact and prefix matches first, the way people type tickers.
    matches.sort((a, b) => this.rank(a.symbol, q) - this.rank(b.symbol, q) || a.symbol.localeCompare(b.symbol));
    this.results = matches.slice(0, 8).map(item => ({
      symbol: item.symbol,
      name: this.names[item.symbol] ?? item.name ?? null,
      price: this.livePrices[item.symbol] ?? item.price,
      reason: this.unavailable(item.symbol)
    }));
    this.highlighted.set(0);
  }

  onKey(event: KeyboardEvent): void {
    const count = this.results.length;
    if (event.key === 'ArrowDown' && count) {
      event.preventDefault();
      this.highlighted.update(i => (i + 1) % count);
    } else if (event.key === 'ArrowUp' && count) {
      event.preventDefault();
      this.highlighted.update(i => (i - 1 + count) % count);
    } else if (event.key === 'Enter' && count) {
      event.preventDefault();
      this.choose(this.results[this.highlighted()]);
    } else if (event.key === 'Escape') {
      this.close();
      this.input?.nativeElement.blur();
    } else {
      // Leave other keys and navigation without results to the input's default behavior.
    }
  }

  /** Focuses the search, e.g. when a page opens it. */
  focus(): void {
    this.input?.nativeElement.focus();
  }

  choose(result: SearchResult): void {
    if (result.reason) {
      return;
    }
    this.pick.emit(result.symbol);
    this.picked.emit({ symbol: result.symbol, name: result.name });
    this.query = '';
    this.results = [];
    this.close();
    this.input?.nativeElement.blur();
  }

  close(): void {
    this.open.set(false);
  }

  onBlur(): void {
    // Let a click on a result land before the list disappears.
    setTimeout(() => this.close(), 150);
  }

  private rank(symbol: string, q: string): number {
    return symbol === q ? 0 : symbol.startsWith(q) ? 1 : 2;
  }
}
