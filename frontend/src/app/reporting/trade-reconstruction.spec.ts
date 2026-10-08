import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TradeTimeline } from '../services/trade-ops';
import { TradeReconstructionComponent } from './trade-reconstruction';

describe('TradeReconstructionComponent', () => {
  let fixture: ComponentFixture<TradeReconstructionComponent>;
  let http: HttpTestingController;
  const searchUrl = '/api/order/ops/orders';
  const trade = {
    orderId: 'order-1', submittedAt: '2026-10-08T14:02:05Z', clientEmail: 'alice.johnson@leap.com',
    accountNumber: 'ACC-1001', symbol: 'AAPL', side: 'BUY' as const, quantity: 10, status: 'FILLED'
  };
  const timeline: TradeTimeline = {
    trade, quotedPrice: 150, maxSlippagePercent: 1,
    steps: [
      { at: '2026-10-08T14:02:05.100Z', type: 'SUBMITTED', description: 'BUY 10 AAPL placed' },
      { at: '2026-10-08T14:02:06Z', type: 'FILLED', description: 'Order filled' }
    ]
  };

  const el = (selector: string): HTMLElement | null => fixture.nativeElement.querySelector(selector);
  const all = (selector: string): HTMLElement[] => Array.from(fixture.nativeElement.querySelectorAll(selector));

  function search(orderId: string): void {
    fixture.componentInstance.criteria.orderId = orderId;
    (el('[data-testid="ops-search"]') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  function respond(body: object, status = 200): void {
    http.expectOne(() => true).flush(body, status === 200 ? {} : { status, statusText: 'Error' });
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TradeReconstructionComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TradeReconstructionComponent);
    fixture.detectChanges();
  });
  afterEach(() => http.verify());

  // Verify nothing is looked up, and so nothing logged, until Trading Operations searches.
  it('makes no request until a search', () => {
    http.expectNone(() => true);
  });

  // Verify an empty search is caught without a request.
  it('needs at least one search field', () => {
    search(' ');
    http.expectNone(() => true);
    expect(el('.error-message')?.textContent).toContain('at least one search field');
  });

  // Verify the search sends the filled-in fields and lists the matching trades.
  it('lists the trades a search finds', () => {
    search('order-1');
    const request = http.expectOne(r => r.url === searchUrl);
    expect(request.request.params.get('orderId')).toBe('order-1');
    request.flush([trade]);
    fixture.detectChanges();

    const rows = all('[data-testid="ops-result"]');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('alice.johnson@leap.com');
    expect(rows[0].textContent).toContain('BUY 10 AAPL');
  });

  // Verify a search with no matches says so.
  it('says when no trades match', () => {
    search('nothing');
    respond([]);
    expect(el('[data-testid="ops-no-results"]')).not.toBeNull();
  });

  // Verify viewing a trade shows its steps in order with UTC times, and back returns to the results.
  it('shows a trade timeline', () => {
    search('order-1');
    respond([trade]);
    (el('[data-testid="ops-result"] button') as HTMLButtonElement).click();
    http.expectOne('/api/order/ops/orders/order-1/timeline').flush(timeline);
    fixture.detectChanges();

    const steps = all('[data-testid="ops-step"]');
    expect(steps.map(step => step.querySelector('strong')?.textContent)).toEqual(['SUBMITTED', 'FILLED']);
    expect(steps[0].textContent).toContain('2026-10-08 14:02:05.100 UTC');
    expect(el('.timeline-header')?.textContent).toContain('tolerance 1%');

    (all('.actions button')[0]).click();
    fixture.detectChanges();
    expect(all('[data-testid="ops-result"]').length).toBe(1);
  });

  // Verify Download CSV fetches the file through the API and saves it under the order's name.
  it('downloads the timeline as CSV', () => {
    search('order-1');
    respond([trade]);
    (el('[data-testid="ops-result"] button') as HTMLButtonElement).click();
    respond(timeline);
    const click = spyOn(HTMLAnchorElement.prototype, 'click');
    spyOn(URL, 'createObjectURL').and.returnValue('blob:csv');
    spyOn(URL, 'revokeObjectURL');

    (el('[data-testid="ops-download"]') as HTMLButtonElement).click();
    http.expectOne('/api/order/ops/orders/order-1/timeline.csv').flush(new Blob(['csv']));

    expect(click).toHaveBeenCalled();
    expect((click.calls.mostRecent().object as HTMLAnchorElement).download).toBe('trade-order-1.csv');
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:csv');
  });

  // Verify order-app's message is shown when a lookup fails.
  it('shows the error when a lookup fails', () => {
    search('order-1');
    respond({ message: 'Give at least one search criterion' }, 400);
    expect(el('.error-message')?.textContent).toContain('Give at least one search criterion');
  });
});
