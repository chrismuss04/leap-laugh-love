import { Component, ElementRef, EventEmitter, OnChanges, Output, SimpleChanges, ViewChild, computed, inject, input, signal } from '@angular/core';
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
 *
 * The app is zoneless, so all template state lives in signals: an HTTP callback or a parent's
 * timer that sets a plain field wouldn't schedule a refresh.
 */
@Component({
    selector: 'app-cash-panel',
    imports: [CommonModule, FormsModule],
    templateUrl: './cash-panel.html',
    styleUrls: ['../panel.css', './cash-panel.css']
})
export class CashPanelComponent implements OnChanges {
  readonly accounts = input<CashAccount[]>([]);
  /**
   * The account the page is about: cash is added to it, and a transfer starts from it if it has
   * cash, or into it if not. Applied when it changes or accounts first arrive, so a manual pick
   * sticks through refreshes.
   */
  readonly focusAccountId = input<string | null>(null);
  /** Cash moved (or may have); balances should be reloaded. */
  @Output() changed = new EventEmitter<void>();
  @Output() openAccount = new EventEmitter<void>();

  @ViewChild('amountInput') amountInput?: ElementRef<HTMLInputElement>;

  readonly mode = signal<CashMode>('deposit');
  /** The account cash is added to. */
  readonly depositId = signal<string | null>(null);
  readonly fromId = signal<string | null>(null);
  readonly toId = signal<string | null>(null);
  readonly amount = signal<number | null>(null);
  readonly note = signal('');
  readonly step = signal<Step>('edit');
  readonly depositResult = signal<CashTransactionResponse | null>(null);
  readonly transferResult = signal<CashTransferResponse | null>(null);
  readonly submitError = signal<string | null>(null);

  money = formatMoney;

  private readonly accountsService = inject(AccountsService);
  private readonly balanceService = inject(BalanceService);

  ngOnChanges(changes: SimpleChanges): void {
    const accounts = this.accounts();
    if (changes['accounts']) {
      const ids = accounts.map(a => a.accountId);
      const depositId = this.depositId();
      if (!depositId || !ids.includes(depositId)) {
        this.depositId.set(ids[0] ?? null);
      }
      const fromId = this.fromId();
      if (!fromId || !ids.includes(fromId)) {
        this.fromId.set(ids[0] ?? null);
      }
      const toId = this.toId();
      if (!toId || !ids.includes(toId) || toId === this.fromId()) {
        this.toId.set(ids.find(id => id !== this.fromId()) ?? null);
      }
    }
    const accountsArrived = changes['accounts'] && !changes['accounts'].previousValue?.length;
    const focus = accounts.find(a => a.accountId === this.focusAccountId());
    if ((changes['focusAccountId'] || accountsArrived) && focus && this.step() === 'edit') {
      this.applyFocus(focus);
    }
  }

  private applyFocus(focus: CashAccount): void {
    this.depositId.set(focus.accountId);
    if (focus.cash > 0) {
      this.fromId.set(focus.accountId);
      if (this.toId() === focus.accountId) {
        this.toId.set(this.accounts().find(a => a.accountId !== focus.accountId)?.accountId ?? null);
      }
    } else {
      this.toId.set(focus.accountId);
      this.fromId.set(this.richestOther(focus.accountId));
    }
  }

  /** Whichever other account has the most cash, to fund from. */
  private richestOther(accountId: string): string | null {
    return [...this.accounts()].filter(a => a.accountId !== accountId)
      .sort((a, b) => b.cash - a.cash)[0]?.accountId ?? null;
  }

  /** Opens the ticket to add cash to an account, e.g. from an account card's Add cash button. */
  startDeposit(accountId: string): void {
    if (this.step() === 'submitting') {
      return;
    }
    this.reset();
    this.mode.set('deposit');
    this.depositId.set(accountId);
    this.focusAmount();
  }

  /** Opens the ticket to transfer out of an account, e.g. from an account card's Transfer button. */
  startTransfer(fromId: string): void {
    if (this.step() === 'submitting') {
      return;
    }
    this.reset();
    this.mode.set('transfer');
    this.fromId.set(fromId);
    if (this.toId() === fromId) {
      this.toId.set(this.accounts().find(a => a.accountId !== fromId)?.accountId ?? null);
    }
    this.focusAmount();
  }

  setMode(mode: CashMode): void {
    if (this.step() === 'edit') {
      this.mode.set(mode);
    }
  }

  readonly depositAccount = computed(() => this.accounts().find(a => a.accountId === this.depositId()));

  readonly from = computed(() => this.accounts().find(a => a.accountId === this.fromId()));

  readonly to = computed(() => this.accounts().find(a => a.accountId === this.toId()));

  readonly validAmount = computed(() => isCashAmount(this.amount()));

  /** Why the ticket can't be reviewed yet, or null if it can. */
  readonly blocker = computed(() => {
    const amount = this.amount();
    const from = this.from();
    if (this.mode() === 'deposit') {
      if (!this.depositAccount()) {
        return 'Choose an account';
      }
    } else {
      if (this.accounts().length < 2) {
        return 'Transfers need a second account';
      }
      if (!from || !this.to()) {
        return 'Choose both accounts';
      }
      if (this.fromId() === this.toId()) {
        return 'Choose two different accounts';
      }
    }
    if (amount == null) {
      return null;
    }
    if (!this.validAmount()) {
      return 'Enter an amount of at least $0.01, to the cent';
    }
    if (this.mode() === 'transfer' && amount > from!.cash) {
      return `Not enough cash (${formatMoney(from!.cash)} available)`;
    }
    return null;
  });

  readonly canReview = computed(() => this.validAmount() && this.blocker() === null);

  swap(): void {
    if (this.step() === 'edit') {
      const [fromId, toId] = [this.fromId(), this.toId()];
      this.fromId.set(toId);
      this.toId.set(fromId);
    }
  }

  transferAll(): void {
    this.amount.set(this.from()?.cash || null);
  }

  onFromChange(): void {
    if (this.toId() === this.fromId()) {
      this.toId.set(this.accounts().find(a => a.accountId !== this.fromId())?.accountId ?? null);
    }
  }

  review(): void {
    if (this.canReview()) {
      this.step.set('review');
    }
  }

  edit(): void {
    this.step.set('edit');
    this.focusAmount();
  }

  submit(): void {
    if (!this.canReview()) {
      return;
    }
    const mode = this.mode();
    this.step.set('submitting');
    this.submitError.set(null);
    const description = this.note().trim() || undefined;
    const request: Observable<CashTransactionResponse | CashTransferResponse> = mode === 'deposit'
      ? this.balanceService.deposit(this.depositId()!, { amount: this.amount()!, description })
      : this.accountsService.transferCash({ fromAccountId: this.fromId()!, toAccountId: this.toId()!, amount: this.amount()!, description });
    request.subscribe({
      next: response => {
        if (mode === 'deposit') {
          this.depositResult.set(response as CashTransactionResponse);
        } else {
          this.transferResult.set(response as CashTransferResponse);
        }
        this.step.set('result');
        this.changed.emit();
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 0 || err.status >= 500) {
          // It may have been booked before the failure, so don't invite a second one.
          this.step.set('unconfirmed');
          this.changed.emit();
          return;
        }
        this.submitError.set(err.error?.message || err.error?.error
          || (mode === 'deposit' ? 'Your cash could not be added.' : 'Your transfer could not be made.') + ' Please try again.');
        this.step.set('review');
      }
    });
  }

  reset(): void {
    this.step.set('edit');
    this.amount.set(null);
    this.note.set('');
    this.depositResult.set(null);
    this.transferResult.set(null);
    this.submitError.set(null);
  }

  accountName(accountId: string | null | undefined): string {
    return this.accounts().find(a => a.accountId === accountId)?.name ?? '—';
  }

  private focusAmount(): void {
    setTimeout(() => this.amountInput?.nativeElement.focus());
  }
}
