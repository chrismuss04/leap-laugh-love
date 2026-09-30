import { Component, ElementRef, EventEmitter, Input, OnChanges, Output, SimpleChanges, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { AccountsService, CashTransferResponse } from '../../services/accounts';
import { BalanceService, CashTransactionResponse } from '../../services/balance';
import { formatMoney } from '../../shared/format';
import { isCashAmount } from '../cash';

/** What the cash panel needs to know about one account. */
export interface CashAccount {
  accountId: string;
  /** Display name, e.g. "Brokerage ··4821" - see accountName(). */
  name: string;
  cash: number;
}

/** Add new cash to one account, or move cash between two of the client's own accounts. */
export type CashMode = 'deposit' | 'transfer';

type Step = 'edit' | 'review' | 'submitting' | 'result' | 'unconfirmed';

/**
 * Moving money, at any time: add cash to an account, or transfer cash between accounts. Both are
 * the same ticket - pick the account(s), type an amount, review, submit - with an Add cash /
 * Transfer toggle like the trade ticket's Buy / Sell. Positions never move, only cash.
 */
@Component({
    selector: 'app-cash-panel',
    imports: [CommonModule, FormsModule],
    templateUrl: './cash-panel.html',
    styleUrls: ['../panel.css', './cash-panel.css']
})
export class CashPanelComponent implements OnChanges {
  @Input() accounts: CashAccount[] = [];
  /**
   * The account the page is about: cash is added to it, and a transfer starts from it if it has
   * cash, or into it if not. Applied when it changes or accounts first arrive, so a manual pick
   * sticks through refreshes.
   */
  @Input() focusAccountId: string | null = null;
  /** Cash moved (or may have); balances should be reloaded. */
  @Output() changed = new EventEmitter<void>();
  @Output() openAccount = new EventEmitter<void>();

  @ViewChild('amountInput') amountInput?: ElementRef<HTMLInputElement>;

  mode: CashMode = 'deposit';
  /** The account cash is added to. */
  depositId: string | null = null;
  fromId: string | null = null;
  toId: string | null = null;
  amount: number | null = null;
  note = '';
  step: Step = 'edit';
  depositResult: CashTransactionResponse | null = null;
  transferResult: CashTransferResponse | null = null;
  submitError: string | null = null;

  money = formatMoney;

  private readonly accountsService = inject(AccountsService);
  private readonly balanceService = inject(BalanceService);

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['accounts']) {
      const ids = this.accounts.map(a => a.accountId);
      if (!this.depositId || !ids.includes(this.depositId)) {
        this.depositId = ids[0] ?? null;
      }
      if (!this.fromId || !ids.includes(this.fromId)) {
        this.fromId = ids[0] ?? null;
      }
      if (!this.toId || !ids.includes(this.toId) || this.toId === this.fromId) {
        this.toId = ids.find(id => id !== this.fromId) ?? null;
      }
    }
    const accountsArrived = changes['accounts'] && !changes['accounts'].previousValue?.length;
    const focus = this.accounts.find(a => a.accountId === this.focusAccountId);
    if ((changes['focusAccountId'] || accountsArrived) && focus && this.step === 'edit') {
      this.applyFocus(focus);
    }
  }

  private applyFocus(focus: CashAccount): void {
    this.depositId = focus.accountId;
    if (focus.cash > 0) {
      this.fromId = focus.accountId;
      if (this.toId === focus.accountId) {
        this.toId = this.accounts.find(a => a.accountId !== focus.accountId)?.accountId ?? null;
      }
    } else {
      this.toId = focus.accountId;
      this.fromId = this.richestOther(focus.accountId);
    }
  }

  /** Whichever other account has the most cash, to fund from. */
  private richestOther(accountId: string): string | null {
    return [...this.accounts].filter(a => a.accountId !== accountId)
      .sort((a, b) => b.cash - a.cash)[0]?.accountId ?? null;
  }

  /** Opens the ticket to add cash to an account, e.g. from an account card's Add cash button. */
  startDeposit(accountId: string): void {
    if (this.step === 'submitting') {
      return;
    }
    this.reset();
    this.mode = 'deposit';
    this.depositId = accountId;
    this.focusAmount();
  }

  /** Opens the ticket to transfer out of an account, e.g. from an account card's Transfer button. */
  startTransfer(fromId: string): void {
    if (this.step === 'submitting') {
      return;
    }
    this.reset();
    this.mode = 'transfer';
    this.fromId = fromId;
    if (this.toId === fromId) {
      this.toId = this.accounts.find(a => a.accountId !== fromId)?.accountId ?? null;
    }
    this.focusAmount();
  }

  setMode(mode: CashMode): void {
    if (this.step === 'edit') {
      this.mode = mode;
    }
  }

  get depositAccount(): CashAccount | undefined {
    return this.accounts.find(a => a.accountId === this.depositId);
  }

  get from(): CashAccount | undefined {
    return this.accounts.find(a => a.accountId === this.fromId);
  }

  get to(): CashAccount | undefined {
    return this.accounts.find(a => a.accountId === this.toId);
  }

  get validAmount(): boolean {
    return isCashAmount(this.amount);
  }

  /** Why the ticket can't be reviewed yet, or null if it can. */
  get blocker(): string | null {
    if (this.mode === 'deposit') {
      if (!this.depositAccount) {
        return 'Choose an account';
      }
    } else {
      if (this.accounts.length < 2) {
        return 'Transfers need a second account';
      }
      if (!this.from || !this.to) {
        return 'Choose both accounts';
      }
      if (this.fromId === this.toId) {
        return 'Choose two different accounts';
      }
    }
    if (this.amount == null) {
      return null;
    }
    if (!this.validAmount) {
      return 'Enter an amount of at least $0.01, to the cent';
    }
    if (this.mode === 'transfer' && this.amount > this.from!.cash) {
      return `Not enough cash (${formatMoney(this.from!.cash)} available)`;
    }
    return null;
  }

  get canReview(): boolean {
    return this.validAmount && this.blocker === null;
  }

  swap(): void {
    if (this.step === 'edit') {
      [this.fromId, this.toId] = [this.toId, this.fromId];
    }
  }

  transferAll(): void {
    this.amount = this.from?.cash || null;
  }

  onFromChange(): void {
    if (this.toId === this.fromId) {
      this.toId = this.accounts.find(a => a.accountId !== this.fromId)?.accountId ?? null;
    }
  }

  review(): void {
    if (this.canReview) {
      this.step = 'review';
    }
  }

  edit(): void {
    this.step = 'edit';
    this.focusAmount();
  }

  submit(): void {
    if (!this.canReview) {
      return;
    }
    this.step = 'submitting';
    this.submitError = null;
    const description = this.note.trim() || undefined;
    const request: Observable<CashTransactionResponse | CashTransferResponse> = this.mode === 'deposit'
      ? this.balanceService.deposit(this.depositId!, { amount: this.amount!, description })
      : this.accountsService.transferCash({ fromAccountId: this.fromId!, toAccountId: this.toId!, amount: this.amount!, description });
    request.subscribe({
      next: response => {
        if (this.mode === 'deposit') {
          this.depositResult = response as CashTransactionResponse;
        } else {
          this.transferResult = response as CashTransferResponse;
        }
        this.step = 'result';
        this.changed.emit();
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 0 || err.status >= 500) {
          // It may have been booked before the failure, so don't invite a second one.
          this.step = 'unconfirmed';
          this.changed.emit();
          return;
        }
        this.submitError = err.error?.message || err.error?.error
          || (this.mode === 'deposit' ? 'Your cash could not be added.' : 'Your transfer could not be made.') + ' Please try again.';
        this.step = 'review';
      }
    });
  }

  reset(): void {
    this.step = 'edit';
    this.amount = null;
    this.note = '';
    this.depositResult = null;
    this.transferResult = null;
    this.submitError = null;
  }

  accountName(accountId: string | null | undefined): string {
    return this.accounts.find(a => a.accountId === accountId)?.name ?? '—';
  }

  private focusAmount(): void {
    setTimeout(() => this.amountInput?.nativeElement.focus());
  }
}
