import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AccountsService } from '../../services/accounts';
import { BalanceService } from '../../services/balance';
import type { CashTransactionResponse } from '../../services/balance';
import { AccountSummary } from '../../services/holdings';
import { OpenAccountPanelComponent } from './open-account-panel';
import type { MockedObject } from 'vitest';
import { createSpyObj } from '../../../testing/create-spy-obj';

describe('OpenAccountPanelComponent', () => {
  const account: AccountSummary = {
    accountId: 'new', accountNumber: 'ACC-0009', status: 'ACTIVE', baseCurrency: 'USD', tradingEnabled: true,
    maxSlippagePercent: null, createdAt: '', inactiveSince: null
  };

  let fixture: ComponentFixture<OpenAccountPanelComponent>;
  let panel: OpenAccountPanelComponent;
  let accounts: MockedObject<AccountsService>;
  let balance: MockedObject<BalanceService>;

  beforeEach(() => {
    accounts = createSpyObj('AccountsService', ['openAccount']);
    balance = createSpyObj('BalanceService', ['deposit']);
    accounts.openAccount.mockReturnValue(of(account));
    TestBed.configureTestingModule({
      imports: [OpenAccountPanelComponent],
      providers: [{ provide: AccountsService, useValue: accounts }, { provide: BalanceService, useValue: balance }]
    });
    fixture = TestBed.createComponent(OpenAccountPanelComponent);
    panel = fixture.componentInstance;
    vi.spyOn(panel.opened, 'emit').mockImplementation(() => {});
    vi.spyOn(panel.deposited, 'emit').mockImplementation(() => {});
    fixture.detectChanges();
  });

  it('treats an empty or zero amount as no deposit', () => {
    expect(panel.wantsDeposit()).toBe(false);
    panel.amount.set(0);
    expect(panel.wantsDeposit()).toBe(false);
    expect(panel.amountError()).toBeNull();
  });

  it('flags an amount that is not whole cents', () => {
    panel.amount.set(1.234);
    expect(panel.wantsDeposit()).toBe(true);
    expect(panel.amountError()).toContain('at least $0.01');
  });

  it('refuses to submit with an invalid amount', () => {
    panel.amount.set(0.001);
    panel.submit();
    expect(accounts.openAccount).not.toHaveBeenCalled();
  });

  it('opens an account with no starting deposit', () => {
    panel.submit();
    expect(accounts.openAccount).toHaveBeenCalledWith({ baseCurrency: 'USD' });
    expect(panel.opened.emit).toHaveBeenCalledWith(account);
    expect(panel.step()).toBe('result');
    expect(panel.deposit()).toBe('none');
    expect(balance.deposit).not.toHaveBeenCalled();
    fixture.detectChanges();
  });

  it('ignores a second submit while not on the form', () => {
    panel.submit();
    panel.submit();
    expect(accounts.openAccount).toHaveBeenCalledTimes(1);
  });

  it('opens an account and deposits the starting cash', () => {
    balance.deposit.mockReturnValue(of({ balanceAfter: 250 } as CashTransactionResponse));
    panel.amount.set(250);
    panel.submit();
    expect(balance.deposit).toHaveBeenCalledWith('new', { amount: 250, description: 'Starting cash' });
    expect(panel.cash()).toBe(250);
    expect(panel.deposit()).toBe('done');
    expect(panel.step()).toBe('result');
    expect(panel.deposited.emit).toHaveBeenCalled();
    fixture.detectChanges();
  });

  it('returns to the form with the server message when opening fails', () => {
    accounts.openAccount.mockReturnValue(throwError(() => ({ error: { message: 'Limit reached' } })));
    panel.submit();
    expect(panel.submitError()).toBe('Limit reached');
    expect(panel.step()).toBe('form');
    fixture.detectChanges();
  });

  it('falls back to a generic message when opening fails without one', () => {
    accounts.openAccount.mockReturnValue(throwError(() => ({})));
    panel.submit();
    expect(panel.submitError()).toBe('We couldn’t open your account. Please try again.');
  });

  it('reports a refused deposit as failed so it can be retried', () => {
    balance.deposit.mockReturnValue(throwError(() => ({ status: 400, error: { error: 'Too large' } })));
    panel.amount.set(250);
    panel.submit();
    expect(panel.deposit()).toBe('failed');
    expect(panel.depositError()).toBe('Too large');
    expect(panel.deposited.emit).not.toHaveBeenCalled();
    expect(panel.step()).toBe('result');

    balance.deposit.mockReturnValue(of({ balanceAfter: 250 } as CashTransactionResponse));
    panel.makeDeposit();
    expect(panel.deposit()).toBe('done');
    expect(panel.depositError()).toBeNull();
  });

  it('has no message for a refused deposit that gives none', () => {
    balance.deposit.mockReturnValue(throwError(() => ({ status: 422 })));
    panel.amount.set(250);
    panel.submit();
    expect(panel.depositError()).toBeNull();
  });

  it('reports an unconfirmed deposit as unknown, never inviting a retry', () => {
    balance.deposit.mockReturnValue(throwError(() => ({ status: 0 })));
    panel.amount.set(250);
    panel.submit();
    expect(panel.deposit()).toBe('unknown');
    expect(panel.deposited.emit).toHaveBeenCalled();
    fixture.detectChanges();
  });

  it('does not deposit without an opened account or a valid amount', () => {
    panel.makeDeposit();
    panel.account.set(account);
    panel.amount.set(0.001);
    panel.makeDeposit();
    expect(balance.deposit).not.toHaveBeenCalled();
  });

  it('is busy while opening or depositing', () => {
    expect(panel.busy()).toBe(false);
    panel.step.set('opening');
    expect(panel.busy()).toBe(true);
    panel.step.set('depositing');
    expect(panel.busy()).toBe(true);
  });

  it('resets to an empty form', () => {
    panel.amount.set(5);
    panel.account.set(account);
    panel.cash.set(5);
    panel.step.set('result');
    panel.reset();
    expect(panel.step()).toBe('form');
    expect(panel.amount()).toBeNull();
    expect(panel.account()).toBeNull();
    expect(panel.cash()).toBe(0);
  });
});
