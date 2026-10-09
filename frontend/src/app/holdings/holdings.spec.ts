import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { HoldingsService } from '../services/holdings';
import { HoldingsComponent } from './holdings';
import type { MockedObject } from 'vitest';
import { createSpyObj } from '../../testing/create-spy-obj';

describe('HoldingsComponent', () => {
  let auth: MockedObject<AuthService>;
  let holdings: MockedObject<HoldingsService>;

  function create(authenticated: boolean): HoldingsComponent {
    auth.isAuthenticated.mockReturnValue(authenticated);
    const fixture = TestBed.createComponent(HoldingsComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    auth = createSpyObj('AuthService', ['isAuthenticated', 'logout']);
    holdings = createSpyObj('HoldingsService', ['getHoldings']);
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
    holdings.getHoldings.mockReturnValue(of({ accounts: [{ accountId: 'a', accountNumber: '1', baseCurrency: 'USD', positions: [] }] }));
    const component = create(true);
    expect(component.accounts().length).toBe(1);
    expect(component.loading()).toBe(false);
    expect(component.error()).toBeNull();
  });

  it('shows the server message when loading fails, or a fallback', () => {
    holdings.getHoldings.mockReturnValue(throwError(() => ({ error: { message: 'Down' } })));
    const component = create(true);
    expect(component.error()).toBe('Down');
    expect(component.loading()).toBe(false);

    holdings.getHoldings.mockReturnValue(throwError(() => ({})));
    component.loadHoldings();
    expect(component.error()).toBe('Failed to load holdings');
  });

  it('signs out through the auth service', () => {
    holdings.getHoldings.mockReturnValue(of({ accounts: [] }));
    create(true).logout();
    expect(auth.logout).toHaveBeenCalled();
  });
});
