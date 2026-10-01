import { Component, EventEmitter, Output, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { AccountsService } from '../../services/accounts';
import { BalanceService } from '../../services/balance';
import { AccountSummary } from '../../services/holdings';
import { formatMoney } from '../../shared/format';
import { accountName } from '../../dashboard/models';
import { isCashAmount } from '../cash';

type Step = 'form' | 'opening' | 'depositing' | 'result';

/**
 * How the starting deposit went: none asked for, made, refused (safe to retry), or unknown - the
 * request failed part-way, so it may have been booked and retrying could deposit twice.
 */
type DepositOutcome = 'none' | 'done' | 'failed' | 'unknown';

/**
 * Opens another brokerage account under the signed-in profile. Nothing about the client is asked
 * again - identity is shared - only an optional starting cash amount, deposited as soon as the
 * account exists. Opening and depositing are two requests, so the confirmation reports each.
 *
 * The app is zoneless, so all template state lives in signals: an HTTP callback that sets a plain
 * field wouldn't schedule a refresh.
 */
@Component({
    selector: 'app-open-account-panel',
    imports: [CommonModule, FormsModule],
    templateUrl: './open-account-panel.html',
    styleUrls: ['../panel.css', './open-account-panel.css']
})
export class OpenAccountPanelComponent {
  @Output() opened = new EventEmitter<AccountSummary>();
  /** The new account's cash changed (or may have); balances should be reloaded. */
  @Output() deposited = new EventEmitter<void>();
  /** Continue to adding cash to the new account. */
  @Output() fund = new EventEmitter<string>();
  @Output() closed = new EventEmitter<void>();

  readonly baseCurrency = 'USD';

  readonly step = signal<Step>('form');
  /** Starting cash to deposit; empty for none. */
  readonly amount = signal<number | null>(null);
  readonly account = signal<AccountSummary | null>(null);
  readonly deposit = signal<DepositOutcome>('none');
  readonly depositError = signal<string | null>(null);
  readonly cash = signal(0);
  readonly submitError = signal<string | null>(null);

  money = formatMoney;
  accountName = accountName;

  private readonly accountsService = inject(AccountsService);
  private readonly balanceService = inject(BalanceService);

  readonly wantsDeposit = computed(() => {
    const amount = this.amount();
    return amount != null && amount !== 0;
  });

  /** Why the form can't be submitted, or null if it can. */
  readonly amountError = computed(() =>
    this.wantsDeposit() && !isCashAmount(this.amount()) ? 'Enter an amount of at least $0.01, to the cent' : null);

  readonly busy = computed(() => this.step() === 'opening' || this.step() === 'depositing');

  submit(): void {
    if (this.step() !== 'form' || this.amountError()) {
      return;
    }
    this.step.set('opening');
    this.submitError.set(null);
    this.accountsService.openAccount({ baseCurrency: this.baseCurrency }).subscribe({
      next: account => {
        this.account.set(account);
        this.opened.emit(account);
        if (this.wantsDeposit()) {
          this.makeDeposit();
        } else {
          this.step.set('result');
        }
      },
      error: (err: HttpErrorResponse) => {
        this.submitError.set(err.error?.message || err.error?.error || 'We couldn’t open your account. Please try again.');
        this.step.set('form');
      }
    });
  }

  /** Deposits the starting cash into the opened account; also the retry after a refused deposit. */
  makeDeposit(): void {
    const account = this.account();
    const amount = this.amount();
    if (!account || !isCashAmount(amount)) {
      return;
    }
    this.step.set('depositing');
    this.depositError.set(null);
    this.balanceService.deposit(account.accountId, { amount: amount!, description: 'Starting cash' }).subscribe({
      next: response => {
        this.cash.set(response.balanceAfter);
        this.deposit.set('done');
        this.step.set('result');
        this.deposited.emit();
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 0 || err.status >= 500) {
          // It may have been booked before the failure, so don't invite a second deposit.
          this.deposit.set('unknown');
          this.deposited.emit();
        } else {
          this.deposit.set('failed');
          this.depositError.set(err.error?.message || err.error?.error || null);
        }
        this.step.set('result');
      }
    });
  }

  reset(): void {
    this.step.set('form');
    this.amount.set(null);
    this.account.set(null);
    this.deposit.set('none');
    this.depositError.set(null);
    this.cash.set(0);
    this.submitError.set(null);
  }
}
