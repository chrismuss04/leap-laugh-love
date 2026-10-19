import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { AuthService, SESSION_DATA_PREFIX } from '../../services/auth.service';
import { isTradable } from '../../shared/tradable';

/** One instrument in the basket. */
export interface BasketItem {
  symbol: string;
  name: string;
}

/** Interface for storing a basket in session storage. */
interface StoredBasket {
  owner: string;
  items: BasketItem[];
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
    effect(() => this.save({ owner: this.owner, items: this.items() }));
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
    this.announcement.set(`Added ${upper}. Your basket has ${plural(this.count())}.`);
    return null;
  }

  remove(symbol: string): void {
    if (this.symbols().includes(symbol)) {
      this.items.update(items => items.filter(item => item.symbol !== symbol));
      this.announcement.set(`Removed ${symbol}. Your basket has ${plural(this.count())}.`);
    }
  }

  clear(): void {
    if (!this.isEmpty()) {
      this.announcement.set('Your basket was cleared.');
    }
    this.items.set([]);
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
  }

  private save(basket: StoredBasket): void {
    try {
      sessionStorage.setItem(BASKET_KEY, JSON.stringify(basket));
    } catch {
      // Storage is full or blocked: the basket still works, it just won't survive a refresh.
    }
  }
}
