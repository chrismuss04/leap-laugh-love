import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { AuthService, SESSION_DATA_PREFIX } from '../services/auth.service';
import { OrderConditions, OrderLine, conditionsProblem, isPlainMarket, pickConditions, placementOrder } from '../shared/order-plan';
import { formatMoney } from '../shared/format';
import { ToolId, findTool } from './tools-catalog';

/** Which tool an order came from, or 'netted' for one the tray combined from opposite orders. */
export type PlanSource = ToolId | 'netted';

/** One order in the tray. Prices aren't kept: the tray values orders live, and placing quotes them afresh. */
export interface TrayOrder extends OrderLine {
  id: string;
  source: PlanSource;
}

interface StoredPlan {
  owner: string;
  accountId: string | null;
  orders: TrayOrder[];
}

export const TRADE_PLAN_KEY = `${SESSION_DATA_PREFIX}trade-plan`;

const SOURCES: ReadonlySet<string> = new Set(['rebalance', 'raise-cash', 'basket', 'draw', 'netted']);

/** "Rebalance", or "Netted" for an order the tray combined. */
export function sourceLabel(source: PlanSource): string {
  return source === 'netted' ? 'Netted' : findTool(source)?.planLabel ?? source;
}

/**
 * A conditional order's terms in a few words - "Limit $182.00 · GTC · Bracket $214.00 / $184.00" -
 * or '' for a plain market order for the day.
 */
export function conditionsLabel(line: OrderConditions): string {
  const parts: string[] = [];
  switch (line.orderType ?? 'MARKET') {
    case 'LIMIT':
      parts.push(`Limit ${formatMoney(line.limitPrice)}`);
      break;
    case 'STOP':
      parts.push(`Stop ${formatMoney(line.stopPrice)}`);
      break;
    case 'STOP_LIMIT':
      parts.push(`Stop ${formatMoney(line.stopPrice)} → limit ${formatMoney(line.limitPrice)}`);
      break;
    case 'MARKET':
      break;
  }
  if (line.timeInForce === 'GTC') {
    parts.push('GTC');
  }
  if (line.bracket) {
    parts.push(`Take profit ${formatMoney(line.bracket.takeProfit)} / stop loss ${formatMoney(line.bracket.stopLoss)}`);
  }
  return parts.join(' · ');
}

/** Whole shares, at least one. */
function validQuantity(value: number): boolean {
  return Number.isInteger(value) && value >= 1;
}

/** Whether something read back from session storage is a well-formed tray order. */
function isStoredOrder(o: Partial<TrayOrder> | null | undefined): o is TrayOrder {
  if (typeof o?.id !== 'string' || typeof o.symbol !== 'string') {
    return false;
  }
  if (o.side !== 'BUY' && o.side !== 'SELL') {
    return false;
  }
  return validQuantity(o.quantity ?? Number.NaN) && SOURCES.has(o.source ?? '')
    && conditionsProblem({ ...o, side: o.side }) === null;
}

function plural(count: number): string {
  return `${count} order${count === 1 ? '' : 's'}`;
}

/**
 * Trading Tools: the trade plan tray's contents - a shopping cart for orders. Tools add their
 * orders, tagged with the tool; the client edits, removes, nets and (in a later story) places them.
 *
 * The plan belongs to one account and is kept in session storage under the signed-in client, so it
 * survives moving between tools and a refresh but not the session: AuthService clears it on sign-out.
 */
@Injectable()
export class TradePlanService {
  private readonly owner = inject(AuthService).getEmail() ?? '';

  /** The account the plan's orders are for; null until the tools page picks one. */
  readonly accountId = signal<string | null>(null);
  readonly orders = signal<TrayOrder[]>([]);
  /** The latest change, for a screen reader's live region. */
  readonly announcement = signal('');
  /** Counts the times a tool has put orders in, so the tray can mark their arrival. */
  readonly arrivals = signal(0);

  readonly count = computed(() => this.orders().length);
  readonly isEmpty = computed(() => this.orders().length === 0);

  /** Sells, then buys, each by symbol - the order they would be placed in. */
  readonly sorted = computed(() => [...this.orders()].sort(placementOrder));

  /**
   * Symbols the plan both buys and sells at market, which could be netted into one order. Limit,
   * stop and bracket orders trade at their own prices, so they're never netted.
   */
  readonly conflicts = computed(() => {
    const sides = new Map<string, Set<string>>();
    for (const order of this.orders().filter(isPlainMarket)) {
      sides.set(order.symbol, (sides.get(order.symbol) ?? new Set()).add(order.side));
    }
    return [...sides].filter(([, set]) => set.size > 1).map(([symbol]) => symbol).sort((a, b) => a.localeCompare(b));
  });

  /** Every symbol in the plan. */
  readonly symbols = computed(() => [...new Set(this.orders().map(o => o.symbol))].sort((a, b) => a.localeCompare(b)));

  private sequence = 0;

  constructor() {
    this.restore();
    effect(() => this.save({ owner: this.owner, accountId: this.accountId(), orders: this.orders() }));
  }

  /**
   * Adds a tool's orders, replacing any it added before. Orders of fewer than one whole share, or
   * with conditions order-app would refuse, are left out. A plan for another account starts the tray over; the tools page asks the client
   * before it ever gets that far.
   */
  addPlan(source: ToolId, accountId: string, lines: OrderLine[]): void {
    if (this.accountId() !== accountId) {
      this.orders.set([]);
      this.accountId.set(accountId);
    }
    const added = lines
      .filter(line => validQuantity(line.quantity) && conditionsProblem(line) === null)
      .map(line => ({
        id: this.nextId(), source, symbol: line.symbol, side: line.side, quantity: line.quantity, ...pickConditions(line)
      }));
    const replaced = this.orders().some(o => o.source === source);
    this.orders.update(orders => [...orders.filter(o => o.source !== source), ...added]);

    const label = sourceLabel(source);
    if (added.length) {
      this.arrivals.update(n => n + 1);
    }
    if (!added.length) {
      this.announcement.set(replaced ? `${label}'s orders were removed from your plan.` : `${label} had no orders to add.`);
    } else {
      this.announcement.set(replaced
        ? `${label}'s orders were replaced: ${plural(added.length)} in your plan from ${label}.`
        : `${plural(added.length)} from ${label} added to your plan.`);
    }
  }

  remove(id: string): void {
    const order = this.orders().find(o => o.id === id);
    if (order) {
      this.orders.update(orders => orders.filter(o => o.id !== id));
      this.announcement.set(`Removed ${order.side.toLowerCase()} ${order.quantity} ${order.symbol} from your plan.`);
    }
  }

  /** Takes out the orders that were placed, keeping the rest to fix and retry. */
  removeMany(ids: readonly string[]): void {
    const gone = new Set(ids);
    this.orders.update(orders => orders.filter(o => !gone.has(o.id)));
  }

  clear(): void {
    if (this.orders().length) {
      this.announcement.set('Your plan was cleared.');
    }
    this.orders.set([]);
  }

  /** Makes an empty plan this account's. A plan with orders keeps its account. */
  adopt(accountId: string): void {
    if (this.isEmpty()) {
      this.accountId.set(accountId);
    }
  }

  /** Changes an order's quantity; returns why it was refused, or null once it's changed. */
  setQuantity(id: string, value: number): string | null {
    if (!validQuantity(value)) {
      return 'Enter a whole number of shares, 1 or more.';
    }
    this.orders.update(orders => orders.map(o => (o.id === id ? { ...o, quantity: value } : o)));
    return null;
  }

  /**
   * Combines every market order on a symbol into the one order that has the same effect, tagged
   * 'netted'. Orders that cancel out leave nothing behind; conditional orders are kept as they are.
   */
  net(symbol: string): void {
    const netted = (o: TrayOrder) => o.symbol === symbol && isPlainMarket(o);
    const onSymbol = this.orders().filter(netted);
    if (!onSymbol.length) {
      return;
    }
    const balance = onSymbol.reduce((sum, o) => sum + (o.side === 'BUY' ? o.quantity : -o.quantity), 0);
    const remaining = this.orders().filter(o => !netted(o));
    if (balance === 0) {
      this.orders.set(remaining);
      this.announcement.set(`The ${symbol} orders cancel out, so they were removed from your plan.`);
      return;
    }
    const side = balance > 0 ? 'BUY' : 'SELL';
    const quantity = Math.abs(balance);
    this.orders.set([...remaining, { id: this.nextId(), source: 'netted', symbol, side, quantity }]);
    this.announcement.set(`The ${symbol} orders were netted into one: ${side.toLowerCase()} ${quantity}.`);
  }

  private nextId(): string {
    return `o${++this.sequence}`;
  }

  /** Picks up this client's plan from earlier in the session; anything else stored is ignored. */
  private restore(): void {
    let stored: StoredPlan | null = null;
    try {
      stored = JSON.parse(sessionStorage.getItem(TRADE_PLAN_KEY) ?? 'null');
    } catch {
      return;
    }
    if (stored?.owner !== this.owner || !Array.isArray(stored.orders)) {
      return;
    }
    const orders = stored.orders.filter(isStoredOrder);
    this.accountId.set(typeof stored.accountId === 'string' ? stored.accountId : null);
    this.orders.set(orders);
    this.sequence = Math.max(0, ...orders.map(o => Number(o.id.slice(1)) || 0));
  }

  private save(plan: StoredPlan): void {
    try {
      sessionStorage.setItem(TRADE_PLAN_KEY, JSON.stringify(plan));
    } catch {
      // Storage is full or blocked: the plan still works, it just won't survive a refresh.
    }
  }
}
