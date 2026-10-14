import { Injectable, OnDestroy } from '@angular/core';
import { Subject } from 'rxjs';
import { EventStream } from './event-stream';
import { OrderUpdate } from './order';
import { parseSseFrame } from './sse-frame';

/**
 * The client's own order updates, pushed as they happen: a limit, stop or trailing order filling,
 * being refused when its price is reached, or expiring while they watch.
 *
 * The server sends a heartbeat every 15 seconds, so a silent connection is treated as dead.
 * Anything missed while disconnected is caught by the dashboard's poll of open orders, so this
 * only has to be fast, not complete.
 */
@Injectable({
  providedIn: 'root'
})
export class OrderStreamService extends EventStream implements OnDestroy {
  /** Three missed heartbeats means the connection is dead even though it never closed. */
  protected readonly stallMs = 45_000;
  protected readonly stallCheckMs = 5_000;

  /** Each update, as it arrives. */
  readonly updates = new Subject<OrderUpdate>();

  private wanted = false;

  /** Opens the stream, if it isn't already open. */
  start(): void {
    if (this.wanted) {
      return;
    }
    this.wanted = true;
    this.attempt = 0;
    this.connect();
  }

  /** Closes the stream, e.g. on leaving the dashboard or logging out. */
  stop(): void {
    this.wanted = false;
    this.disconnect();
    this.status.set('idle');
  }

  ngOnDestroy(): void {
    this.stop();
    this.updates.complete();
  }

  protected streamUrl(): string | null {
    return this.wanted ? '/api/order/orders/stream' : null;
  }

  protected handleFrame(frame: string): void {
    const parsed = parseSseFrame(frame);
    if (parsed?.event !== 'order') {
      return;
    }
    try {
      const update = JSON.parse(parsed.data) as OrderUpdate;
      this.zone.run(() => this.updates.next(update));
    } catch {
      // A malformed frame is dropped; the dashboard's next poll of open orders catches up.
    }
  }
}
