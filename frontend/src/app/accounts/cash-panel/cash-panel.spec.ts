import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { AccountsService } from '../../services/accounts';
import { BalanceService } from '../../services/balance';
import { CashAccount, CashPanelComponent } from './cash-panel';
import type { MockedObject } from 'vitest';
import { createSpyObj } from '../../../testing/create-spy-obj';

describe('CashPanelComponent', () => {
  const rich: CashAccount = { accountId: 'a', name: 'Brokerage ··0001', cash: 1000 };
  const poor: CashAccount = { accountId: 'b', name: 'Brokerage ··0002', cash: 0 };
  const third: CashAccount = { accountId: 'c', name: 'Brokerage ··0003', cash: 50 };

  let fixture: ComponentFixture<CashPanelComponent>;
  let panel: CashPanelComponent;
  let balance: MockedObject<BalanceService>;
  let accounts: MockedObject<AccountsService>;

  function setAccounts(list: CashAccount[], focus: string | null = null): void {
    fixture.componentRef.setInput('accounts', list);
    fixture.componentRef.setInput('focusAccountId', focus);
    fixture.detectChanges();
  }

  const deposit = { cashLedgerId: 'l', accountId: 'a', entryType: 'DEPOSIT', amount: 10, currency: 'USD',
    balanceAfter: 1010, createdAt: '', description: null };
  const transfer = { transferId: 't', fromAccountId: 'a', toAccountId: 'b', amount: 10, currency: 'USD',
    fromBalanceAfter: 990, toBalanceAfter: 10, createdAt: '' };

  beforeEach(() => {
    balance = createSpyObj('BalanceService', ['deposit']);
    accounts = createSpyObj('AccountsService', ['transferCash']);
    TestBed.configureTestingModule({
      imports: [CashPanelComponent],
      providers: [{ provide: BalanceService, useValue: balance }, { provide: AccountsService, useValue: accounts }]
    });
    fixture = TestBed.createComponent(CashPanelComponent);
    panel = fixture.componentInstance;
  });

  it('selects the first account and a different destination when accounts arrive', () => {
    setAccounts([rich, poor]);
    expect(panel.depositId()).toBe('a');
    expect(panel.fromId()).toBe('a');
    expect(panel.toId()).toBe('b');
  });

  it('keeps a manual pick through a refresh, and replaces one that disappears', () => {
    setAccounts([rich, poor]);
    panel.depositId.set('b');
    setAccounts([{ ...rich, cash: 5 }, poor]);
    expect(panel.depositId()).toBe('b');
    setAccounts([rich]);
    expect(panel.depositId()).toBe('a');
    expect(panel.toId()).toBeNull();
  });

  it('starts from the focused account when it has cash', () => {
    setAccounts([poor, rich], 'a');
    expect(panel.depositId()).toBe('a');
    expect(panel.fromId()).toBe('a');
    expect(panel.toId()).toBe('b');
  });

  it('transfers into the focused account, funded by the richest other, when it has no cash', () => {
    setAccounts([rich, third, poor], 'b');
    expect(panel.toId()).toBe('b');
    expect(panel.fromId()).toBe('a');
  });

  it('moves the destination off a focused source', () => {
    setAccounts([rich, poor]);
    panel.toId.set('a');
    fixture.componentRef.setInput('focusAccountId', 'a');
    fixture.detectChanges();
    expect(panel.toId()).toBe('b');
  });

  it('does not refocus while a ticket is being reviewed', () => {
    setAccounts([rich, poor]);
    panel.amount.set(5);
    panel.review();
    fixture.componentRef.setInput('focusAccountId', 'b');
    fixture.detectChanges();
    expect(panel.depositId()).toBe('a');
  });

  describe('starting a ticket', () => {
    beforeEach(() => setAccounts([rich, poor, third]));

    it('opens a deposit for an account and focuses the amount', fakeAsync(() => {
      panel.mode.set('transfer');
      panel.amount.set(3);
      panel.startDeposit('b');
      tick();
      expect(panel.mode()).toBe('deposit');
      expect(panel.depositId()).toBe('b');
      expect(panel.amount()).toBeNull();
    }));

    it('opens a transfer out of an account and repoints a clashing destination', () => {
      panel.toId.set('a');
      panel.startTransfer('a');
      expect(panel.mode()).toBe('transfer');
      expect(panel.fromId()).toBe('a');
      expect(panel.toId()).not.toBe('a');
    });

    it('ignores starts while submitting', () => {
      panel.step.set('submitting');
      panel.startDeposit('b');
      panel.startTransfer('b');
      expect(panel.step()).toBe('submitting');
      expect(panel.depositId()).toBe('a');
    });
  });

  describe('validation', () => {
    beforeEach(() => setAccounts([rich, poor]));

    it('only switches mode while editing', () => {
      panel.setMode('transfer');
      expect(panel.mode()).toBe('transfer');
      panel.amount.set(5);
      panel.review();
      panel.setMode('deposit');
      expect(panel.mode()).toBe('transfer');
    });

    it('needs an account, and a second account to transfer', () => {
      setAccounts([]);
      expect(panel.blocker()).toBe('Choose an account');
      panel.mode.set('transfer');
      expect(panel.blocker()).toBe('Transfers need a second account');
      setAccounts([rich]);
      expect(panel.blocker()).toBe('Transfers need a second account');
    });

    it('needs both accounts, and two different ones', () => {
      panel.mode.set('transfer');
      panel.toId.set(null);
      expect(panel.blocker()).toBe('Choose both accounts');
      panel.toId.set('a');
      expect(panel.blocker()).toBe('Choose two different accounts');
    });

    it('rejects amounts that are not whole cents, and transfers above the available cash', () => {
      expect(panel.blocker()).toBeNull();
      panel.amount.set(1.234);
      expect(panel.blocker()).toContain('at least $0.01');
      panel.mode.set('transfer');
      panel.amount.set(2000);
      expect(panel.blocker()).toBe('Not enough cash ($1,000.00 available)');
      expect(panel.canReview()).toBe(false);
      panel.amount.set(20);
      expect(panel.blocker()).toBeNull();
      expect(panel.canReview()).toBe(true);
    });

    it('does not review or submit an invalid ticket', () => {
      panel.review();
      panel.submit();
      expect(panel.step()).toBe('edit');
      expect(balance.deposit).not.toHaveBeenCalled();
    });

    it('swaps accounts only while editing', () => {
      panel.swap();
      expect(panel.fromId()).toBe('b');
      expect(panel.toId()).toBe('a');
      panel.step.set('review');
      panel.swap();
      expect(panel.fromId()).toBe('b');
    });

    it('moves all of the source cash, or clears the amount when there is none', () => {
      panel.transferAll();
      expect(panel.amount()).toBe(1000);
      panel.fromId.set('b');
      panel.transferAll();
      expect(panel.amount()).toBeNull();
    });

    it('repoints the destination when the source changes onto it', () => {
      panel.fromId.set('b');
      panel.toId.set('b');
      panel.onFromChange();
      expect(panel.toId()).toBe('a');
    });

    it('returns to editing from review', fakeAsync(() => {
      panel.amount.set(5);
      panel.review();
      expect(panel.step()).toBe('review');
      panel.edit();
      tick();
      expect(panel.step()).toBe('edit');
    }));

    it('names accounts, with a dash for unknown ones', () => {
      expect(panel.accountName('a')).toBe(rich.name);
      expect(panel.accountName('zzz')).toBe('—');
      expect(panel.accountName(null)).toBe('—');
    });
  });

  describe('submitting', () => {
    beforeEach(() => {
      setAccounts([rich, poor]);
      vi.spyOn(panel.changed, 'emit').mockImplementation(() => {});
    });

    it('adds cash with a trimmed note and shows the result', () => {
      balance.deposit.mockReturnValue(of(deposit));
      panel.amount.set(10);
      panel.note.set('  savings ');
      panel.submit();
      expect(balance.deposit).toHaveBeenCalledWith('a', { amount: 10, description: 'savings' });
      expect(panel.depositResult()).toEqual(deposit);
      expect(panel.step()).toBe('result');
      expect(panel.changed.emit).toHaveBeenCalled();
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Added $10.00');
    });

    it('transfers cash without a blank note and shows the result', () => {
      accounts.transferCash.mockReturnValue(of(transfer));
      panel.mode.set('transfer');
      panel.amount.set(10);
      panel.note.set('   ');
      panel.submit();
      expect(accounts.transferCash).toHaveBeenCalledWith(
        { fromAccountId: 'a', toAccountId: 'b', amount: 10, description: undefined });
      expect(panel.transferResult()).toEqual(transfer);
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Moved $10.00');
    });

    it('shows a spinner while the request is in flight', () => {
      balance.deposit.mockReturnValue(new Subject());
      panel.amount.set(10);
      panel.submit();
      expect(panel.step()).toBe('submitting');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.spinner')).not.toBeNull();
    });

    it('does not invite a repeat when the outcome is unknown', () => {
      balance.deposit.mockReturnValue(throwError(() => ({ status: 503 })));
      panel.amount.set(10);
      panel.submit();
      expect(panel.step()).toBe('unconfirmed');
      expect(panel.changed.emit).toHaveBeenCalled();
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('We couldn\'t confirm your deposit');

      accounts.transferCash.mockReturnValue(throwError(() => ({ status: 0 })));
      panel.reset();
      panel.mode.set('transfer');
      panel.amount.set(10);
      panel.submit();
      expect(panel.step()).toBe('unconfirmed');
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('We couldn\'t confirm your transfer');
    });

    it('returns to review with the server message on a rejection', () => {
      balance.deposit.mockReturnValue(throwError(() => ({ status: 400, error: { message: 'Over the limit' } })));
      panel.amount.set(10);
      panel.submit();
      expect(panel.step()).toBe('review');
      expect(panel.submitError()).toBe('Over the limit');
    });

    it('falls back to a generic message per mode', () => {
      balance.deposit.mockReturnValue(throwError(() => ({ status: 400, error: {} })));
      panel.amount.set(10);
      panel.submit();
      expect(panel.submitError()).toBe('Your cash could not be added. Please try again.');

      accounts.transferCash.mockReturnValue(throwError(() => ({ status: 400 })));
      panel.reset();
      panel.mode.set('transfer');
      panel.amount.set(10);
      panel.submit();
      expect(panel.submitError()).toBe('Your transfer could not be made. Please try again.');
    });

    it('clears the ticket on reset', () => {
      panel.amount.set(10);
      panel.note.set('x');
      panel.reset();
      expect(panel.amount()).toBeNull();
      expect(panel.note()).toBe('');
      expect(panel.step()).toBe('edit');
    });
  });

  it('renders the edit form and a prompt to open a second account', () => {
    setAccounts([rich]);
    panel.mode.set('transfer');
    fixture.detectChanges();
    vi.spyOn(panel.openAccount, 'emit').mockImplementation(() => {});
    const open = fixture.nativeElement.querySelector('.placeholder button') as HTMLButtonElement;
    open.click();
    expect(panel.openAccount.emit).toHaveBeenCalled();
  });
});
