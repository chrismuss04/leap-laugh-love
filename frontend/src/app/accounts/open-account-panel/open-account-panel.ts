import { Component, EventEmitter, Output, inject } from '@angular/core';
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

  step: Step = 'form';
  /** Starting cash to deposit; empty for none. */
  amount: number | null = null;
  account: AccountSummary | null = null;
  deposit: DepositOutcome = 'none';
  depositError: string | null = null;
  cash = 0;
  submitError: string | null = null;

  money = formatMoney;
  accountName = accountName;

  private readonly accountsService = inject(AccountsService);
  private readonly balanceService = inject(BalanceService);

  get wantsDeposit(): boolean {
    return this.amount != null && this.amount !== 0;
  }

  /** Why the form can't be submitted, or null if it can. */
  get amountError(): string | null {
    return this.wantsDeposit && !isCashAmount(this.amount) ? 'Enter an amount of at least $0.01, to the cent' : null;
  }

  get busy(): boolean {
    return this.step === 'opening' || this.step === 'depositing';
  }

  submit(): void {
    if (this.step !== 'form' || this.amountError) {
      return;
    }
    this.step = 'opening';
    this.submitError = null;
    this.accountsService.openAccount({ baseCurrency: this.baseCurrency }).subscribe({
      next: account => {
        this.account = account;
        this.opened.emit(account);
        if (this.wantsDeposit) {
          this.makeDeposit();
        } else {
          this.step = 'result';
        }
      },
      error: (err: HttpErrorResponse) => {
        this.submitError = err.error?.message || err.error?.error || 'We couldn’t open your account. Please try again.';
        this.step = 'form';
      }
    });
  }

  /** Deposits the starting cash into the opened account; also the retry after a refused deposit. */
  makeDeposit(): void {
    if (!this.account || !isCashAmount(this.amount)) {
      return;
    }
    this.step = 'depositing';
    this.depositError = null;
    this.balanceService.deposit(this.account.accountId, { amount: this.amount!, description: 'Starting cash' }).subscribe({
      next: response => {
        this.cash = response.balanceAfter;
        this.deposit = 'done';
        this.step = 'result';
        this.deposited.emit();
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 0 || err.status >= 500) {
          // It may have been booked before the failure, so don't invite a second deposit.
          this.deposit = 'unknown';
          this.deposited.emit();
        } else {
          this.deposit = 'failed';
          this.depositError = err.error?.message || err.error?.error || null;
        }
        this.step = 'result';
      }
    });
  }

  reset(): void {
    this.step = 'form';
    this.amount = null;
    this.account = null;
    this.deposit = 'none';
    this.depositError = null;
    this.cash = 0;
    this.submitError = null;
  }
}
