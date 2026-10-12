import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';
import { PriceStreamService } from './price-stream';
import type { Mock } from 'vitest';

describe('PriceStreamService', () => {
  const realSetTimeout = window.setTimeout.bind(window);
  const settle = () => new Promise<void>(resolve => realSetTimeout(resolve, 20));
  const encoder = new TextEncoder();

  let service: PriceStreamService;
  let token: string | null;
  let fetchSpy: Mock;
  let streams: ReadableStreamDefaultController<Uint8Array>[];

  /** A fetch() that serves a controllable event stream and honours abort. */
  function serveStream(status = 200): void {
    fetchSpy.mockImplementation((_url: string, init: RequestInit) => {
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          streams.push(controller);
          init.signal?.addEventListener('abort', () => {
            try { controller.error(new DOMException('aborted', 'AbortError')); } catch { /* already closed */ }
          });
        }
      });
      return Promise.resolve(new Response(status === 204 ? null : body, { status }));
    });
  }

  function send(text: string): void {
    streams[streams.length - 1].enqueue(encoder.encode(text));
  }

  const tick = (symbol: string, price: number) => `event: price\ndata: {"symbol":"${symbol}","price":${price}}\n\n`;
  const flush = () => (service as unknown as { flush(): void }).flush();

  beforeEach(() => {
    token = 'jwt';
    streams = [];
    fetchSpy = vi.spyOn(window, 'fetch').mockReturnValue(undefined as never);
    serveStream();
    TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { getToken: () => token } }] });
    service = TestBed.inject(PriceStreamService);
  });

  afterEach(() => {
    service.stop();
    vi.useRealTimers();
  });

  it('does not connect without a token or symbols', () => {
    token = null;
    service.watch(['AAPL']);
    service.watch([]);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(service.status()).toBe('idle');
  });

  it('connects with the bearer token and reports live', async () => {
    service.watch(['aapl', 'msft']);
    expect(service.status()).toBe('connecting');
    await settle();

    const [url, init] = fetchSpy.mock.lastCall!;
    expect(url).toBe('/api/marketdata/stream?symbols=AAPL%2CMSFT');
    expect(init.headers.Authorization).toBe('Bearer jwt');
    expect(service.status()).toBe('live');
  });

  it('reconnects only when the symbol set grows', async () => {
    service.watch(['AAPL']);
    service.watch(['aapl']);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    service.watch(['MSFT']);
    expect(fetchSpy).toHaveBeenCalledTimes(2);
    await settle();
  });

  it('publishes a tick that has never been shown', async () => {
    service.watch(['AAPL']);
    await settle();
    send(tick('AAPL', 101.5));
    await settle();
    flush();
    expect(service.prices()).toEqual({ AAPL: 101.5 });
  });

  it('parses frames split across chunks and with CRLF line endings', async () => {
    service.watch(['AAPL']);
    await settle();
    send('event: price\r\ndata: {"symbol":"AAPL",');
    send('"price":7}\r\n\r\n');
    await settle();
    flush();
    expect(service.prices()['AAPL']).toBe(7);
  });

  it('ignores other events, empty data and malformed frames', async () => {
    service.watch(['AAPL']);
    await settle();
    send(': heartbeat\n\nevent: ping\ndata: {"symbol":"AAPL","price":1}\n\nevent: price\n\nevent: price\ndata: not-json\n\n');
    await settle();
    flush();
    expect(service.prices()).toEqual({});
  });

  it('holds a small move back until the symbol is due, but lets a big move through', async () => {
    service.watch(['AAPL']);
    await settle();
    send(tick('AAPL', 100));
    await settle();
    flush();

    const internals = service as unknown as { publishedAt: Record<string, number>; dueAt: Record<string, number> };
    send(tick('AAPL', 100.001));
    await settle();
    flush();
    expect(service.prices()['AAPL']).toBe(100);

    // A big move still waits out the minimum gap, then goes through ahead of its quiet window.
    send(tick('AAPL', 110));
    await settle();
    flush();
    expect(service.prices()['AAPL']).toBe(100);
    internals.publishedAt['AAPL'] -= 2_000;
    flush();
    expect(service.prices()['AAPL']).toBe(110);

    // Once the quiet window has passed, even a tiny move is published.
    internals.dueAt['AAPL'] = 0;
    send(tick('AAPL', 110.001));
    await settle();
    flush();
    expect(service.prices()['AAPL']).toBe(110.001);
  });

  it('stops everything on a 401', async () => {
    serveStream(401);
    service.watch(['AAPL']);
    await settle();
    expect(service.status()).toBe('idle');
    expect(service.prices()).toEqual({});
  });

  it('reconnects with backoff when the server errors', async () => {
    serveStream(500);
    service.watch(['AAPL']);
    await settle();
    expect(service.status()).toBe('reconnecting');
  });

  it('reconnects when a request fails outright', async () => {
    fetchSpy.mockReturnValue(Promise.reject(new Error('offline')));
    service.watch(['AAPL']);
    await settle();
    expect(service.status()).toBe('reconnecting');
  });

  it('reconnects when the stream closes', async () => {
    service.watch(['AAPL']);
    await settle();
    streams[0].close();
    await settle();
    expect(service.status()).toBe('reconnecting');
  });

  it('treats a silent connection as dead', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date());
    service.watch(['AAPL']);
    await settle();
    expect(service.status()).toBe('live');
    vi.advanceTimersByTime(12_000);
    expect(service.status()).toBe('reconnecting');
  });

  it('forgets everything on stop', async () => {
    service.watch(['AAPL']);
    await settle();
    send(tick('AAPL', 5));
    await settle();
    flush();
    service.stop();
    expect(service.prices()).toEqual({});
    expect(service.status()).toBe('idle');
  });

  it('stops when destroyed', async () => {
    service.watch(['AAPL']);
    await settle();
    service.ngOnDestroy();
    expect(service.status()).toBe('idle');
  });
});
