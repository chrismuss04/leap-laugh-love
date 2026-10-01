import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AuthService } from '../services/auth.service';
import { OrderHistoryComponent } from './order-history';

describe('OrderHistoryComponent', () => {
  let fixture: ComponentFixture<OrderHistoryComponent>;
  let http: HttpTestingController;
  let auth: jasmine.SpyObj<AuthService>;
  const url = '/api/order/orders/history';
  const emptyPage = { content: [], number: 0, totalPages: 0, size: 20, totalElements: 0, first: true, last: true };

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['isAuthenticated', 'logout']);
    auth.isAuthenticated.and.returnValue(true);
    TestBed.configureTestingModule({
      imports: [OrderHistoryComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: AuthService, useValue: auth }]
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(OrderHistoryComponent);
  });
  afterEach(() => { http.verify(); fixture.destroy(); });

  function finishLoad(): void {
    http.expectOne(r => r.url === url).flush(emptyPage);
    fixture.detectChanges();
  }

  // Verify signed-out visitors do not trigger an order-history request.
  it('skips signed-out loading', () => {
    auth.isAuthenticated.and.returnValue(false);
    fixture.detectChanges();
    http.expectNone(r => r.url === url);
  });

  // Verify the loading indicator becomes an empty-state message after an empty response.
  it('shows empty history', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.loading')).not.toBeNull();
    finishLoad();
    expect(fixture.nativeElement.querySelector('.loading')).toBeNull();
    expect(fixture.nativeElement.querySelector('.no-orders').textContent).toContain('No orders found');
  });

  // Verify API failures display a useful message and stop the loading indicator.
  it('shows loading failure', () => {
    fixture.detectChanges();
    http.expectOne(r => r.url === url).flush({ message: 'Orders unavailable' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.error-message').textContent).toContain('Orders unavailable');
    expect(fixture.nativeElement.querySelector('.loading')).toBeNull();
    expect(fixture.nativeElement.querySelector('.no-orders')).toBeNull();
  });

  // Verify clearing date filters removes their dependencies and returns to the first page.
  it('clears date filters', () => {
    fixture.detectChanges();
    finishLoad();
    const component = fixture.componentInstance;
    component.year.set(2024); component.month.set(2); component.day.set(29);
    component.currentPage.set(3);
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.filter-bar button').click();
    const request = http.expectOne(r => r.url === url);
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.has('year')).toBeFalse();
    expect(component.month()).toBeNull();
    expect(component.day()).toBeNull();
    request.flush(emptyPage);
  });

  // Verify pagination cannot request pages before the first or beyond the last page.
  it('respects page boundaries', () => {
    fixture.detectChanges();
    finishLoad();
    fixture.componentInstance.prevPage();
    fixture.componentInstance.nextPage();
    http.expectNone(r => r.url === url);
  });

  // Verify clicking an order opens its fill details and clicking again closes them.
  it('toggles fill details', () => {
    fixture.detectChanges();
    http.expectOne(r => r.url === url).flush({ ...emptyPage, totalPages: 1, content: [{
      orderId: 'o1', symbol: 'AAPL', side: 'BUY', quantity: 1, status: 'ACCEPTED',
      submittedAt: '2026-01-01T12:00:00Z', filledAt: null, executions: []
    }] });
    fixture.detectChanges();
    const row = fixture.nativeElement.querySelector('[data-testid="order-row"]');
    row.click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="order-fills"]').textContent).toContain('No fills');
    row.click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="order-fills"]')).toBeNull();
  });
});
