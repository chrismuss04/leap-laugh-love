import { NgZone, inject, signal } from '@angular/core';
import { AuthService } from './auth.service';

export type StreamStatus = 'idle' | 'connecting' | 'live' | 'reconnecting';

/**
 * One authenticated server-sent-events connection, shared by the price and order streams.
 *
 * The streams are JWT-secured and the browser's EventSource cannot send an Authorization header,
 * so this reads the stream with fetch() and splits the SSE frames itself, outside Angular's zone.
 * It reconnects with backoff, and treats a connection that goes quiet for too long as dead even
 * though it never closed.
 */
export abstract class EventStream {
  private static readonly MAX_BACKOFF_MS = 30_000;

  readonly status = signal<StreamStatus>('idle');

  protected readonly zone = inject(NgZone);
  private readonly auth = inject(AuthService);

  protected controller?: AbortController;
  private retryTimer?: ReturnType<typeof setTimeout>;
  protected attempt = 0;

  /** This long without a byte means the connection is dead. */
  protected abstract readonly stallMs: number;
  /** How often the stall check runs. */
  protected abstract readonly stallCheckMs: number;

  /** The URL to stream from, or null when nothing should be streamed. */
  protected abstract streamUrl(): string | null;

  /** Handles one complete SSE frame. Runs outside Angular's zone. */
  protected abstract handleFrame(frame: string): void;

  /** Closes the stream; also called when the login has ended. */
  abstract stop(): void;

  /** Aborts the open connection and any pending reconnect. */
  protected disconnect(): void {
    this.controller?.abort();
    this.controller = undefined;
    clearTimeout(this.retryTimer);
  }

  protected connect(): void {
    clearTimeout(this.retryTimer);
    this.controller?.abort();
    const token = this.auth.getToken();
    const url = this.streamUrl();
    if (!token || url === null) {
      return;
    }
    const controller = new AbortController();
    this.controller = controller;
    this.status.set(this.attempt === 0 ? 'connecting' : 'reconnecting');
    this.zone.runOutsideAngular(() => this.read(url, token, controller));
  }

  private async read(url: string, token: string, controller: AbortController): Promise<void> {
    let lastDataAt = Date.now();
    const watchdog = setInterval(() => {
      if (Date.now() - lastDataAt > this.stallMs && this.controller === controller) {
        controller.abort();
        this.scheduleReconnect();
      }
    }, this.stallCheckMs);
    try {
      const response = await fetch(url, {
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

  private scheduleReconnect(): void {
    if (this.streamUrl() === null) {
      return;
    }
    const delay = Math.min(EventStream.MAX_BACKOFF_MS, 1000 * 2 ** this.attempt);
    this.attempt++;
    this.zone.run(() => this.status.set('reconnecting'));
    this.retryTimer = setTimeout(() => this.connect(), delay);
  }
}
