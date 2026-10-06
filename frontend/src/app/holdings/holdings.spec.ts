import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { HoldingsService } from '../services/holdings';
import { HoldingsComponent } from './holdings';

describe('HoldingsComponent', () => {
  let auth: jasmine.SpyObj<AuthService>;
  let holdings: jasmine.SpyObj<HoldingsService>;

  function create(authenticated: boolean): HoldingsComponent {
    auth.isAuthenticated.and.returnValue(authenticated);
    const fixture = TestBed.createComponent(HoldingsComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    auth = jasmine.createSpyObj('AuthService', ['isAuthenticated', 'logout']);
    holdings = jasmine.createSpyObj('HoldingsService', ['getHoldings']);
    TestBed.configureTestingModule({
      imports: [HoldingsComponent],
      providers: [{ provide: AuthService, useValue: auth }, { provide: HoldingsService, useValue: holdings }]
    });
  });

  it('loads nothing when signed out', () => {
    create(false);
    expect(holdings.getHoldings).not.toHaveBeenCalled();
  });

  it('loads the holdings when signed in', () => {
    holdings.getHoldings.and.returnValue(of({ accounts: [{ accountId: 'a', accountNumber: '1', baseCurrency: 'USD', positions: [] }] }));
    const component = create(true);
    expect(component.accounts().length).toBe(1);
    expect(component.loading()).toBeFalse();
    expect(component.error()).toBeNull();
  });

  it('shows the server message when loading fails, or a fallback', () => {
    holdings.getHoldings.and.returnValue(throwError(() => ({ error: { message: 'Down' } })));
    const component = create(true);
    expect(component.error()).toBe('Down');
    expect(component.loading()).toBeFalse();

    holdings.getHoldings.and.returnValue(throwError(() => ({})));
    component.loadHoldings();
    expect(component.error()).toBe('Failed to load holdings');
  });

  it('signs out through the auth service', () => {
    holdings.getHoldings.and.returnValue(of({ accounts: [] }));
    create(true).logout();
    expect(auth.logout).toHaveBeenCalled();
  });
});
