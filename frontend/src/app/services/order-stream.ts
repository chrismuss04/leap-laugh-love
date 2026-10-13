import { Injectable, NgZone, OnDestroy, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { AuthService } from './auth.service';
import { OrderUpdate } from './order';

export type OrderStreamStatus = 'idle' | 'connecting' | 'live' | 'reconnecting';

/**
 * The client's own order updates, pushed as they happen: a limit, stop or trailing order filling,
 * being refused when its price is reached, or expiring while they watch.
 *
 * Like the price stream, it is JWT-secured and the browser's EventSource cannot send an
 * Authorization header, so this reads the stream with fetch() and parses the SSE frames itself,
 * outside Angular's zone. It reconnects with backoff, and treats a silent connection as dead: the
 * server sends a heartbeat every 15 seconds. Anything missed while disconnected is caught by the
 * dashboard's poll of open orders, so this only has to be fast, not complete.
 */
@Injectable({
  providedIn: 'root'
})
export class OrderStreamService implements OnDestroy {
  private static readonly MAX_BACKOFF_MS = 30_000;
  /** Three missed heartbeats means the connection is dead even though it never closed. */
  private static readonly STALL_MS = 45_000;

  /** Each update, as it arrives. */
  readonly updates = new Subject<OrderUpdate>();
  readonly status = signal<OrderStreamStatus>('idle');

  private readonly auth = inject(AuthService);
  private readonly zone = inject(NgZone);

  private controller?: AbortController;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private attempt = 0;
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
    this.controller?.abort();
    this.controller = undefined;
    clearTimeout(this.retryTimer);
    this.status.set('idle');
  }

  ngOnDestroy(): void {
    this.stop();
    this.updates.complete();
  }

  private connect(): void {
    clearTimeout(this.retryTimer);
    this.controller?.abort();
    const token = this.auth.getToken();
    if (!this.wanted || !token) {
      return;
    }
    const controller = new AbortController();
    this.controller = controller;
    this.status.set(this.attempt === 0 ? 'connecting' : 'reconnecting');
    this.zone.runOutsideAngular(() => this.read(token, controller));
  }

  private async read(token: string, controller: AbortController): Promise<void> {
    let lastDataAt = Date.now();
    const watchdog = setInterval(() => {
      if (Date.now() - lastDataAt > OrderStreamService.STALL_MS && this.controller === controller) {
        controller.abort();
        this.scheduleReconnect();
      }
    }, 5_000);
    try {
      const response = await fetch('/api/order/orders/stream', {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: controller.signal
      });
      if (response.status === 401) {
        // The login has ended; the app's own session handling signs the client out.
        this.zone.run(() => this.stop());
        return;
      }
      if (!response.ok || !response.body) {
        throw new Error(`stream responded ${response.status}`);
      }
      this.zone.run(() => this.status.set('live'));
      this.attempt = 0;

      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        lastDataAt = Date.now();
        buffer += value;
        const frames = buffer.split(/\r?\n\r?\n/);
        buffer = frames.pop() ?? '';
        frames.forEach(frame => this.handleFrame(frame));
      }
      throw new Error('stream closed');
    } catch {
      if (controller.signal.aborted) {
        return;
      }
      this.scheduleReconnect();
    } finally {
      clearInterval(watchdog);
    }
  }

  private handleFrame(frame: string): void {
    let event = 'message';
    const data: string[] = [];
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).trimStart());
      }
    }
    if (event !== 'order' || data.length === 0) {
      return;
    }
    try {
      const update = JSON.parse(data.join('\n')) as OrderUpdate;
      this.zone.run(() => this.updates.next(update));
    } catch {
      // A malformed frame is dropped; the dashboard's next poll of open orders catches up.
    }
  }

  private scheduleReconnect(): void {
    if (!this.wanted) {
      return;
    }
    const delay = Math.min(OrderStreamService.MAX_BACKOFF_MS, 1000 * 2 ** this.attempt);
    this.attempt++;
    this.zone.run(() => this.status.set('reconnecting'));
    this.retryTimer = setTimeout(() => this.connect(), delay);
  }
}
