import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { AuthService } from './auth.service';
import { OrderUpdate } from './order';
import { OrderStreamService } from './order-stream';

describe('OrderStreamService', () => {
  let service: OrderStreamService;
  let received: OrderUpdate[];
  let fetchMock: ReturnType<typeof vi.fn>;

  const update: OrderUpdate = {
    orderId: 'o1', symbol: 'AAPL', side: 'BUY', quantity: 2, orderType: 'LIMIT', status: 'FILLED',
    fillPrice: 144.9, reason: null, at: '2026-10-12T20:00:00Z'
  };

  /** An SSE response that stays open after sending the given frames. */
  function sse(...frames: string[]): Response {
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(frames.join('')));
      }
    });
    return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
  }

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: AuthService, useValue: { getToken: () => 'token-123' } }
      ]
    });
    service = TestBed.inject(OrderStreamService);
    received = [];
    service.updates.subscribe(u => received.push(u));
  });

  afterEach(() => {
    service.stop();
    vi.unstubAllGlobals();
  });

  it('opens the stream with the login token and goes live', async () => {
    fetchMock.mockResolvedValue(sse('event: ready\ndata: {}\n\n'));
    service.start();
    expect(service.status()).toBe('connecting');

    await vi.waitFor(() => expect(service.status()).toBe('live'));
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/order/orders/stream');
    expect(init.headers.Authorization).toBe('Bearer token-123');
  });

  it('hands on each order update', async () => {
    fetchMock.mockResolvedValue(sse(
      'event: ready\ndata: {}\n\n',
      `event: order\ndata: ${JSON.stringify(update)}\n\n`,
      'event: ping\ndata: {}\n\n'));
    service.start();

    await vi.waitFor(() => expect(received).toEqual([update]));
  });

  it('drops a frame it cannot read and carries on', async () => {
    fetchMock.mockResolvedValue(sse(
      'event: order\ndata: {not json\n\n',
      `event: order\ndata: ${JSON.stringify(update)}\n\n`));
    service.start();

    await vi.waitFor(() => expect(received).toEqual([update]));
  });

  it('opens only one stream however many times it is started', async () => {
    fetchMock.mockResolvedValue(sse());
    service.start();
    service.start();
    await vi.waitFor(() => expect(service.status()).toBe('live'));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('gives up when the login has ended', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
    service.start();

    await vi.waitFor(() => expect(service.status()).toBe('idle'));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('does not connect without a login', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: AuthService, useValue: { getToken: () => null } }]
    });
    const signedOut = TestBed.inject(OrderStreamService);
    signedOut.start();
    expect(fetchMock).not.toHaveBeenCalled();
    signedOut.stop();
  });

  it('has nothing to stream until started', () => {
    (service as unknown as { connect(): void }).connect();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('closes when stopped', async () => {
    fetchMock.mockResolvedValue(sse());
    service.start();
    await vi.waitFor(() => expect(service.status()).toBe('live'));

    service.stop();

    expect(service.status()).toBe('idle');
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
  });
});
