import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ConfirmedOrder, PlanSubmission, SKIPPED_MESSAGE, UNKNOWN_MESSAGE } from './plan-submission';

describe('PlanSubmission', () => {
  let submission: PlanSubmission;
  let http: HttpTestingController;

  const order = (id: string, side: 'BUY' | 'SELL', symbol: string, quantity: number, quotedPrice: number): ConfirmedOrder =>
    ({ id, side, symbol, quantity, quotedPrice });

  const response = (request: TestRequest, status: string, extra: Record<string, unknown> = {}) => {
    const body = request.request.body;
    const price = body.quotedPrice;
    return {
      orderId: `order-${body.symbol}`, accountId: body.accountId, accountNumber: 'ACC-1', symbol: body.symbol,
      side: body.side, quantity: body.quantity, status, submittedAt: '', filledAt: null, rejectionReason: null,
      execution: status === 'FILLED' || status === 'ACCEPTED'
        ? { executionId: 'x', fillQuantity: body.quantity, fillPrice: price, status: 'FILLED', executedAt: '', reason: null }
        : null,
      accountBalanceAfter: null, ...extra
    };
  };

  /** Answers the one request in flight - there is never more than one - then lets the next go out. */
  async function answer(reply: (request: TestRequest) => void): Promise<TestRequest> {
    await Promise.resolve();
    const request = http.expectOne('/api/order/orders');
    reply(request);
    await new Promise(resolve => setTimeout(resolve));
    return request;
  }
  const fill = (request: TestRequest) => request.flush(response(request, 'FILLED'));
  const reject = (message: string) => (request: TestRequest) =>
    request.flush({ message }, { status: 422, statusText: 'Unprocessable Entity' });

  const statuses = () => submission.rows().map(r => `${r.side} ${r.symbol} ${r.status}${r.message ? ` (${r.message})` : ''}`);

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PlanSubmission, provideHttpClient(), provideHttpClientTesting()]
    });
    submission = TestBed.inject(PlanSubmission);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends every sell, then every buy, one at a time, and reports each fill', async () => {
    const done = submission.submit('a1', [
      order('o1', 'BUY', 'MSFT', 1, 400), order('o2', 'SELL', 'AAPL', 2, 150), order('o3', 'BUY', 'NVDA', 2, 50)
    ], 1000, 0);
    expect(submission.busy()).toBe(true);
    expect(statuses()).toEqual(['SELL AAPL submitting', 'BUY MSFT waiting', 'BUY NVDA waiting']);

    const sent: unknown[] = [];
    for (let i = 0; i < 3; i++) {
      sent.push((await answer(fill)).request.body);
    }
    await done;

    expect(sent).toEqual([
      { accountId: 'a1', symbol: 'AAPL', side: 'SELL', quantity: 2, quotedPrice: 150, orderType: 'MARKET' },
      { accountId: 'a1', symbol: 'MSFT', side: 'BUY', quantity: 1, quotedPrice: 400, orderType: 'MARKET' },
      { accountId: 'a1', symbol: 'NVDA', side: 'BUY', quantity: 2, quotedPrice: 50, orderType: 'MARKET' }
    ]);
    expect(statuses()).toEqual(['SELL AAPL filled', 'BUY MSFT filled', 'BUY NVDA filled']);
    expect(submission.phase()).toBe('done');
    expect(submission.summary()).toEqual({ filled: 3, accepted: 0, open: 0, rejected: 0, skipped: 0, unknown: 0 });
    expect(submission.placedIds()).toEqual(['o2', 'o1', 'o3']);
  });

  it('carries on past a rejection in the middle, with the server\'s reason', async () => {
    const done = submission.submit('a1', [
      order('o1', 'BUY', 'AAPL', 1, 100), order('o2', 'BUY', 'MSFT', 1, 100), order('o3', 'BUY', 'NVDA', 1, 100)
    ], 1000, 0);
    await answer(fill);
    await answer(reject('Price moved 2.1% from your quote, beyond your 1% protection'));
    await answer(fill);
    await done;
    expect(statuses()).toEqual([
      'BUY AAPL filled', 'BUY MSFT rejected (Price moved 2.1% from your quote, beyond your 1% protection)', 'BUY NVDA filled'
    ]);
    expect(submission.placedIds()).toEqual(['o1', 'o3']);
  });

  it('skips buys a failed sell would have funded', async () => {
    const done = submission.submit('a1', [
      order('o1', 'SELL', 'AAPL', 10, 150), order('o2', 'BUY', 'MSFT', 3, 400), order('o3', 'BUY', 'NVDA', 1, 50)
    ], 100, 0);
    await answer(reject('Insufficient position quantity - order rejected'));
    // $100 still covers NVDA; nothing goes out for MSFT.
    await answer(fill);
    await done;
    expect(statuses()).toEqual([
      'SELL AAPL rejected (Insufficient position quantity - order rejected)',
      `BUY MSFT skipped (${SKIPPED_MESSAGE})`,
      'BUY NVDA filled'
    ]);
    expect(submission.summary()).toEqual({ filled: 1, accepted: 0, open: 0, rejected: 1, skipped: 1, unknown: 0 });
  });

  it('funds buys from a fill at its actual price', async () => {
    const done = submission.submit('a1', [order('o1', 'SELL', 'AAPL', 2, 150), order('o2', 'BUY', 'MSFT', 1, 299)], 0, 0);
    // The sale filled lower than quoted, at 149: $298 can't buy $299.
    await answer(request => request.flush({
      ...response(request, 'FILLED'),
      execution: { executionId: 'x', fillQuantity: 2, fillPrice: 149, status: 'FILLED', executedAt: '', reason: null }
    }));
    await done;
    expect(statuses()).toEqual(['SELL AAPL filled', `BUY MSFT skipped (${SKIPPED_MESSAGE})`]);
  });

  it('keeps the cash buffer when deciding whether a buy fits', async () => {
    const done = submission.submit('a1', [order('o1', 'BUY', 'AAPL', 10, 100)], 1000);
    await done;
    expect(statuses()).toEqual([`BUY AAPL skipped (${SKIPPED_MESSAGE})`]);
  });

  it('counts an executed-but-settling order as placed', async () => {
    const done = submission.submit('a1', [order('o1', 'SELL', 'AAPL', 1, 150), order('o2', 'BUY', 'MSFT', 1, 140)], 0, 0);
    await answer(request => request.flush(response(request, 'ACCEPTED'), { status: 202, statusText: 'Accepted' }));
    await answer(fill);
    await done;
    expect(statuses()).toEqual(['SELL AAPL accepted', 'BUY MSFT filled']);
    expect(submission.placedIds()).toEqual(['o1', 'o2']);
  });

  describe('outside regular hours', () => {
    it('doesn\'t let a sell queued for the open fund a buy', async () => {
      const done = submission.submit('a1', [order('o1', 'SELL', 'AAPL', 5, 150), order('o2', 'BUY', 'MSFT', 1, 400)], 100, 0);
      await answer(request => request.flush(response(request, 'OPEN'), { status: 201, statusText: 'Created' }));
      await done;
      expect(statuses()).toEqual(['SELL AAPL open', `BUY MSFT skipped (${SKIPPED_MESSAGE})`]);
      expect(submission.placedIds()).toEqual(['o1']);
    });

    it('counts the cash a queued buy holds back', async () => {
      const done = submission.submit('a1', [order('o1', 'BUY', 'AAPL', 1, 100), order('o2', 'BUY', 'MSFT', 1, 100)], 210, 0);
      await answer(request => request.flush(response(request, 'OPEN', { reservedCash: 115 }), { status: 201, statusText: 'Created' }));
      await done;
      expect(statuses()).toEqual(['BUY AAPL open', `BUY MSFT skipped (${SKIPPED_MESSAGE})`]);
    });

    it('assumes a queued buy holds its quoted cost when the server doesn\'t say', async () => {
      const done = submission.submit('a1', [order('o1', 'BUY', 'AAPL', 1, 100), order('o2', 'BUY', 'MSFT', 1, 100)], 150, 0);
      await answer(request => request.flush(response(request, 'OPEN'), { status: 201, statusText: 'Created' }));
      await done;
      expect(statuses()[1]).toBe(`BUY MSFT skipped (${SKIPPED_MESSAGE})`);
    });
  });

  describe('without a clear answer', () => {
    for (const [what, status] of [['the connection drops', 0], ['the server fails part-way', 503]] as const) {
      it(`marks the order unknown when ${what}, never retries it, and counts its cash as spent`, async () => {
        const done = submission.submit('a1', [order('o1', 'BUY', 'AAPL', 1, 100), order('o2', 'BUY', 'MSFT', 1, 100)], 150, 0);
        await answer(request => status === 0
          ? request.error(new ProgressEvent('error'))
          : request.flush({ message: 'Upstream down' }, { status, statusText: 'Service Unavailable' }));
        await done;
        expect(statuses()).toEqual([`BUY AAPL unknown (${UNKNOWN_MESSAGE})`, `BUY MSFT skipped (${SKIPPED_MESSAGE})`]);
        // Unknown orders leave the tray, so retrying from it can't send them twice.
        expect(submission.placedIds()).toEqual(['o1']);
        expect(submission.summary().unknown).toBe(1);
      });
    }

    it('treats a reply still SUBMITTED as unknown', async () => {
      const done = submission.submit('a1', [order('o1', 'SELL', 'AAPL', 1, 100)], 0, 0);
      await answer(request => request.flush(response(request, 'SUBMITTED')));
      await done;
      expect(statuses()).toEqual([`SELL AAPL unknown (${UNKNOWN_MESSAGE})`]);
    });

    it('takes a rejection in a successful reply at its word', async () => {
      const done = submission.submit('a1', [order('o1', 'SELL', 'AAPL', 1, 100), order('o2', 'SELL', 'MSFT', 1, 100)], 0, 0);
      await answer(request => request.flush(response(request, 'REJECTED', { rejectionReason: 'Instrument is not tradable' })));
      await answer(request => request.flush(response(request, 'REJECTED')));
      await done;
      expect(statuses()).toEqual(['SELL AAPL rejected (Instrument is not tradable)', 'SELL MSFT rejected (Rejected)']);
    });

    it('falls back to a generic reason for a bare 4xx', async () => {
      const done = submission.submit('a1', [order('o1', 'SELL', 'AAPL', 1, 100), order('o2', 'SELL', 'MSFT', 1, 100)], 0, 0);
      await answer(request => request.flush({ error: 'Bad Request' }, { status: 400, statusText: 'Bad Request' }));
      await answer(request => request.flush(null, { status: 400, statusText: 'Bad Request' }));
      await done;
      expect(statuses()).toEqual(['SELL AAPL rejected (Bad Request)', 'SELL MSFT rejected (Rejected)']);
    });
  });

  describe('double submit', () => {
    it('ignores a second submit while the first is running', async () => {
      const orders = [order('o1', 'SELL', 'AAPL', 1, 100), order('o2', 'BUY', 'MSFT', 1, 50)];
      const first = submission.submit('a1', orders, 1000, 0);
      const second = submission.submit('a1', orders, 1000, 0);
      await second;
      await answer(fill);
      await answer(fill);
      await first;
      // http.verify() in afterEach proves nothing else went out.
      expect(statuses()).toEqual(['SELL AAPL filled', 'BUY MSFT filled']);
    });

    it('does nothing for an empty plan', async () => {
      await submission.submit('a1', [], 1000);
      expect(submission.phase()).toBe('idle');
    });
  });

  describe('while running', () => {
    it('asks the browser to confirm leaving, until every request has answered', async () => {
      const add = vi.spyOn(window, 'addEventListener');
      const remove = vi.spyOn(window, 'removeEventListener');
      const done = submission.submit('a1', [order('o1', 'SELL', 'AAPL', 1, 100)], 0, 0);
      const listener = add.mock.calls.find(([type]) => type === 'beforeunload')![1] as (e: Event) => void;
      const event = new Event('beforeunload', { cancelable: true });
      listener(event);
      expect(event.defaultPrevented).toBe(true);

      await answer(fill);
      await done;
      expect(remove).toHaveBeenCalledWith('beforeunload', listener);
    });

    it('refuses to reset until done, then starts over', async () => {
      const done = submission.submit('a1', [order('o1', 'SELL', 'AAPL', 1, 100)], 0, 0);
      submission.reset();
      expect(submission.busy()).toBe(true);
      await answer(fill);
      await done;
      submission.reset();
      expect(submission.phase()).toBe('idle');
      expect(submission.rows()).toEqual([]);
    });
  });
});
