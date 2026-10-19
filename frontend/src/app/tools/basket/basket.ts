import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { AuthService, SESSION_DATA_PREFIX } from '../../services/auth.service';
import { isTradable } from '../../shared/tradable';
import { evenSplit, rescale } from './basket-split';

/** One instrument in the basket. */
export interface BasketItem {
  symbol: string;
  name: string;
}

/** Interface for storing a basket in session storage. */
interface StoredBasket {
  owner: string;
  items: BasketItem[];
  split?: StoredSplit;
}

/** The amount to invest and each stock's part of it, in cents, in basket order. */
interface StoredSplit {
  totalCents: number | null;
  amounts: number[];
}

export const BASKET_KEY = `${SESSION_DATA_PREFIX}basket`;
/** A basket is bought together, so it needs at least two stocks... */
export const MIN_BASKET = 2;
/** ...and holds at most ten. */
export const MAX_BASKET = 10;

function isStoredItem(item: Partial<BasketItem> | null | undefined): item is BasketItem {
  return typeof item?.symbol === 'string' && item.symbol.length > 0 && typeof item.name === 'string';
}

function plural(count: number): string {
  return `${count} stock${count === 1 ? '' : 's'}`;
}

/**
 * Basket Builder: the stocks the client has collected to buy together, in the order they were
 * added. Holds up to MAX_BASKET tradable stocks, each once.
 *
 * Kept in session storage under the signed-in client, like the trade plan, so it survives moving
 * between tools and a refresh but not the session: AuthService clears it on sign-out. The basket
 * isn't tied to an account; what it buys is, once it's added to the plan.
 */
@Injectable()
export class BasketService {
  private readonly owner = inject(AuthService).getEmail() ?? '';

  readonly items = signal<BasketItem[]>([]);
  /** The amount the client chose to invest, in cents; null until they enter one. */
  readonly totalCents = signal<number | null>(null);
  /** Each stock's part of the total, in cents, in basket order; always sums to the total. */
  readonly amounts = signal<number[]>([]);
  /** The latest change, for a screen reader's live region. */
  readonly announcement = signal('');

  readonly count = computed(() => this.items().length);
  readonly isEmpty = computed(() => this.items().length === 0);
  readonly isFull = computed(() => this.items().length >= MAX_BASKET);
  /** Enough stocks to buy as a basket. */
  readonly ready = computed(() => this.items().length >= MIN_BASKET);
  readonly symbols = computed(() => this.items().map(item => item.symbol));

  constructor() {
    this.restore();
    effect(() => this.save({
      owner: this.owner, items: this.items(), split: { totalCents: this.totalCents(), amounts: this.amounts() }
    }));
  }

  /** Why a symbol can't be added, in a word or two for a search result, or null when it can. */
  reason(symbol: string): string | null {
    const upper = symbol.toUpperCase();
    if (!isTradable(upper)) {
      return 'Not tradable';
    }
    if (this.symbols().includes(upper)) {
      return 'Already in basket';
    }
    return this.isFull() ? 'Basket full' : null;
  }

  /** Adds a stock to the end of the basket. Returns why it was refused, or null once it's added. */
  add(symbol: string, name: string | null): string | null {
    const upper = symbol.toUpperCase();
    switch (this.reason(upper)) {
      case 'Not tradable':
        return `${upper} can't be traded, so it can't go in a basket.`;
      case 'Already in basket':
        return `${upper} is already in your basket.`;
      case 'Basket full':
        return `A basket holds up to ${MAX_BASKET} stocks. Remove one to add another.`;
    }
    this.items.update(items => [...items, { symbol: upper, name: name || upper }]);
    this.resplit();
    this.announcement.set(`Added ${upper}. Your basket has ${plural(this.count())}.`);
    return null;
  }

  remove(symbol: string): void {
    if (this.symbols().includes(symbol)) {
      this.items.update(items => items.filter(item => item.symbol !== symbol));
      this.resplit();
      this.announcement.set(`Removed ${symbol}. Your basket has ${plural(this.count())}.`);
    }
  }

  clear(): void {
    if (!this.isEmpty()) {
      this.announcement.set('Your basket was cleared.');
    }
    this.items.set([]);
    this.totalCents.set(null);
    this.amounts.set([]);
  }

  /** Changes the amount to invest, keeping each stock's share of it. */
  setTotal(cents: number | null): void {
    this.totalCents.set(cents);
    this.amounts.set(rescale(this.amounts(), cents ?? 0));
  }

  /** A new split of the same total, from the split bar or a typed amount. */
  setAmounts(amounts: number[]): void {
    this.amounts.set(amounts);
  }

  /** A stock came or went: the total is split evenly again. */
  private resplit(): void {
    this.amounts.set(evenSplit(this.totalCents() ?? 0, this.count()));
  }

  /** Picks up this client's basket from earlier in the session; anything else stored is ignored. */
  private restore(): void {
    let stored: StoredBasket | null = null;
    try {
      stored = JSON.parse(sessionStorage.getItem(BASKET_KEY) ?? 'null');
    } catch {
      return;
    }
    if (!stored || stored.owner !== this.owner || !Array.isArray(stored.items)) {
      return;
    }
    const seen = new Set<string>();
    const items = stored.items.filter(isStoredItem)
      .map(item => ({ symbol: item.symbol.toUpperCase(), name: item.name }))
      .filter(item => isTradable(item.symbol) && !seen.has(item.symbol) && seen.add(item.symbol));
    this.items.set(items.slice(0, MAX_BASKET));
    this.restoreSplit(stored.split);
  }

  /** Keeps a stored split only if it still fits the basket exactly; otherwise splits evenly. */
  private restoreSplit(split: StoredSplit | undefined): void {
    const total = split?.totalCents;
    const validTotal = Number.isInteger(total) && total! >= 0 ? total! : null;
    this.totalCents.set(validTotal);
    const amounts = split?.amounts;
    const fits = Array.isArray(amounts) && amounts.length === this.count()
      && amounts.every(a => Number.isInteger(a) && a >= 0)
      && amounts.reduce((sum, a) => sum + a, 0) === (validTotal ?? 0);
    this.amounts.set(fits ? amounts : evenSplit(validTotal ?? 0, this.count()));
  }

  private save(basket: StoredBasket): void {
    try {
      sessionStorage.setItem(BASKET_KEY, JSON.stringify(basket));
    } catch {
      // Storage is full or blocked: the basket still works, it just won't survive a refresh.
    }
  }
}
