import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { OrderService, OrderSide, OrderSubmissionResponse } from '../services/order';
import { DEFAULT_CASH_BUFFER, placementOrder } from '../shared/order-plan';

/**
 * Where one order of a batch stands. 'accepted' executed and is still being confirmed; 'unknown'
 * got no clear answer, so it may or may not have been placed and is never sent again.
 */
export type SubmissionStatus =
  'waiting' | 'submitting' | 'filled' | 'accepted' | 'open' | 'rejected' | 'skipped' | 'unknown';

/** An order as the client confirmed it: its tray id, and the price they saw when they submitted. */
export interface ConfirmedOrder {
  id: string;
  symbol: string;
  side: OrderSide;
  quantity: number;
  quotedPrice: number;
}

export interface SubmissionRow extends ConfirmedOrder {
  status: SubmissionStatus;
  /** The server's reason for a rejection, or why the order was skipped or is unknown. */
  message: string | null;
}

export interface SubmissionSummary {
  filled: number;
  accepted: number;
  open: number;
  rejected: number;
  skipped: number;
  unknown: number;
}

/** Orders that reached the market, one way or another: the tray can let them go. */
const PLACED: readonly SubmissionStatus[] = ['filled', 'accepted', 'open', 'unknown'];

export const SKIPPED_MESSAGE = 'Skipped — not enough cash';
export const UNKNOWN_MESSAGE = 'No answer from the server, so this order may have been placed. Check your orders before trying again.';

/**
 * Trading Tools: sends a confirmed plan through the one-order API, one order at a time - every
 * sell, then every buy - and tracks each result. There is no batch endpoint.
 *
 * It follows the account's buying power as it goes, so a buy that a rejected (or still-open) sell
 * would have funded is skipped rather than sent. Nothing is ever retried: an order whose outcome is
 * unknown stays unknown.
 */
@Injectable()
export class PlanSubmission {
  private readonly orders = inject(OrderService);

  /** 'idle' until Submit; 'submitting' while requests are out; 'done' once all have answered. */
  readonly phase = signal<'idle' | 'submitting' | 'done'>('idle');
  readonly rows = signal<SubmissionRow[]>([]);

  readonly busy = computed(() => this.phase() === 'submitting');

  readonly summary = computed<SubmissionSummary>(() => {
    const count = (status: SubmissionStatus) => this.rows().filter(r => r.status === status).length;
    return {
      filled: count('filled'), accepted: count('accepted'), open: count('open'),
      rejected: count('rejected'), skipped: count('skipped'), unknown: count('unknown')
    };
  });

  /** Tray ids of the orders that were placed (or may have been), which leave the tray. */
  readonly placedIds = computed(() => this.rows().filter(r => PLACED.includes(r.status)).map(r => r.id));

  private readonly beforeUnload = (event: BeforeUnloadEvent) => {
    event.preventDefault();
    event.returnValue = '';
  };

  constructor() {
    inject(DestroyRef).onDestroy(() => window.removeEventListener('beforeunload', this.beforeUnload));
  }

  /**
   * Submits the orders. A second call while one is running - a double click - does nothing.
   *
   * @param accountId the account every order is for
   * @param orders the confirmed orders, in any order
   * @param buyingPower cash less what the account's open orders already hold
   * @param cashBuffer headroom kept on each buy, as the plan was made with
   */
  async submit(accountId: string, orders: ConfirmedOrder[], buyingPower: number,
               cashBuffer = DEFAULT_CASH_BUFFER): Promise<void> {
    if (this.phase() === 'submitting' || !orders.length) {
      return;
    }
    this.phase.set('submitting');
    window.addEventListener('beforeunload', this.beforeUnload);

    const sorted = [...orders].sort(placementOrder);
    this.rows.set(sorted.map(order => ({ ...order, status: 'waiting', message: null })));

    let available = buyingPower;
    try {
      for (const order of sorted) {
        available = await this.submitOne(accountId, order, available, cashBuffer);
      }
    } finally {
      window.removeEventListener('beforeunload', this.beforeUnload);
      this.phase.set('done');
    }
  }

  /** Back to before Submit, for the next review. Refused while orders are still being sent. */
  reset(): void {
    if (this.phase() !== 'submitting') {
      this.phase.set('idle');
      this.rows.set([]);
    }
  }

  /** Sends one order, or skips a buy the cash can't cover. Returns the cash available after it. */
  private async submitOne(accountId: string, order: ConfirmedOrder, available: number, cashBuffer: number): Promise<number> {
    const cost = order.quantity * order.quotedPrice;
    if (order.side === 'BUY' && cost * (1 + cashBuffer) > available + 1e-9) {
      this.update(order.id, 'skipped', SKIPPED_MESSAGE);
      return available;
    }
    this.update(order.id, 'submitting', null);
    try {
      const response = await firstValueFrom(this.orders.submitOrder({
        accountId, symbol: order.symbol, side: order.side, quantity: order.quantity,
        quotedPrice: order.quotedPrice, orderType: 'MARKET'
      }));
      return this.recordResponse(order, response, available);
    } catch (error) {
      this.recordError(order, error);
      // An unknown buy may have spent its cash; count it as spent so later buys don't overdraw.
      const unknown = this.rows().find(r => r.id === order.id)?.status === 'unknown';
      return order.side === 'BUY' && unknown ? available - cost : available;
    }
  }

  private recordResponse(order: ConfirmedOrder, response: OrderSubmissionResponse, available: number): number {
    const executed = (response.execution?.fillQuantity ?? order.quantity) * (response.execution?.fillPrice ?? order.quotedPrice);
    switch (response.status) {
      case 'FILLED':
      case 'ACCEPTED': {
        this.update(order.id, response.status === 'FILLED' ? 'filled' : 'accepted', null);
        return order.side === 'SELL' ? available + executed : available - executed;
      }
      case 'OPEN':
        // A queued sell raises nothing until it fills; a queued buy holds its cash now.
        this.update(order.id, 'open', null);
        return order.side === 'BUY' ? available - (response.reservedCash ?? order.quantity * order.quotedPrice) : available;
      case 'REJECTED':
        this.update(order.id, 'rejected', response.rejectionReason ?? 'Rejected');
        return available;
      default:
        // SUBMITTED or anything new: placed, but not known to have executed.
        this.update(order.id, 'unknown', UNKNOWN_MESSAGE);
        return order.side === 'BUY' ? available - order.quantity * order.quotedPrice : available;
    }
  }

  /**
   * A 4xx was refused before anything was booked, so it's a rejection with the server's reason. No
   * answer (status 0) or a 5xx may have placed the order part-way, so its outcome is unknown.
   */
  private recordError(order: ConfirmedOrder, error: unknown): void {
    const http = error instanceof HttpErrorResponse ? error : null;
    if (!http || http.status === 0 || http.status >= 500) {
      this.update(order.id, 'unknown', UNKNOWN_MESSAGE);
      return;
    }
    this.update(order.id, 'rejected', http.error?.message || http.error?.error || 'Rejected');
  }

  private update(id: string, status: SubmissionStatus, message: string | null): void {
    this.rows.update(rows => rows.map(row => (row.id === id ? { ...row, status, message } : row)));
  }
}
