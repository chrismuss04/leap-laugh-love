import { Component, ElementRef, EventEmitter, Input, OnChanges, Output, SimpleChanges, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TradeAccount } from '../models';
import { OrderService, OrderSide, OrderSubmissionResponse } from '../../services/order';
import { FlashDirective } from '../../shared/flash.directive';
import { formatMoney } from '../../shared/format';

type Step = 'edit' | 'review' | 'submitting' | 'result';

/**
 * Buy/sell ticket for one symbol, modelled on Robinhood's: pick a side, enter whole shares, see
 * the live estimate against buying power (or shares held), review, then submit. Orders are market
 * orders - trading-app fills them immediately at the live ask (buy) or bid (sell).
 */
@Component({
  selector: 'app-trade-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, FlashDirective],
  templateUrl: './trade-panel.html',
  styleUrl: './trade-panel.css'
})
export class TradePanelComponent implements OnChanges {
  @Input() symbol: string | null = null;
  @Input() name: string | null = null;
  @Input() price: number | null = null;
  @Input() accounts: TradeAccount[] = [];
  @Output() placed = new EventEmitter<OrderSubmissionResponse>();

  @ViewChild('quantityInput') quantityInput?: ElementRef<HTMLInputElement>;

  side: OrderSide = 'BUY';
  accountId: string | null = null;
  quantity: number | null = null;
  step: Step = 'edit';
  result: OrderSubmissionResponse | null = null;
  submitError: string | null = null;

  money = formatMoney;

  private readonly orders = inject(OrderService);

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['accounts'] && (!this.accountId || !this.accounts.some(a => a.accountId === this.accountId))) {
      this.accountId = this.accounts[0]?.accountId ?? null;
    }
    if (changes['symbol'] && !changes['symbol'].firstChange && this.step !== 'submitting') {
      this.reset();
    }
  }

  /** Opens the ticket on a side, e.g. from a position row's Buy/Sell button. */
  open(side: OrderSide): void {
    if (this.step === 'submitting') {
      return;
    }
    this.side = side;
    this.reset();
    setTimeout(() => this.quantityInput?.nativeElement.focus());
  }

  setSide(side: OrderSide): void {
    if (this.step === 'edit') {
      this.side = side;
    }
  }

  get account(): TradeAccount | undefined {
    return this.accounts.find(a => a.accountId === this.accountId);
  }

  get sharesHeld(): number {
    return this.symbol ? this.account?.shares[this.symbol] ?? 0 : 0;
  }

  get estimate(): number | null {
    return this.price != null && this.validQuantity ? this.price * this.quantity! : null;
  }

  get validQuantity(): boolean {
    return this.quantity != null && Number.isInteger(this.quantity) && this.quantity >= 1;
  }

  /** Why the order can't be reviewed yet, or null if it can. */
  get blocker(): string | null {
    if (!this.symbol) {
      return 'Choose a symbol to trade';
    }
    if (!this.account) {
      return 'No active account to trade from';
    }
    if (this.price == null) {
      return 'Waiting for a live price…';
    }
    if (this.quantity == null) {
      return null;
    }
    if (!this.validQuantity) {
      return 'Enter a whole number of shares';
    }
    if (this.side === 'BUY' && this.estimate! > this.account.buyingPower) {
      return `Not enough buying power (${formatMoney(this.account.buyingPower)} available)`;
    }
    if (this.side === 'SELL' && this.quantity! > this.sharesHeld) {
      return this.sharesHeld === 0
        ? `You don't own any ${this.symbol} in this account`
        : `You only have ${this.sharesHeld} ${this.sharesHeld === 1 ? 'share' : 'shares'} to sell`;
    }
    return null;
  }

  get canReview(): boolean {
    return this.validQuantity && this.blocker === null;
  }

  sellAll(): void {
    this.quantity = this.sharesHeld || null;
  }

  adjust(delta: number): void {
    this.quantity = Math.max(1, (this.validQuantity ? this.quantity! : 0) + delta);
  }

  review(): void {
    if (this.canReview) {
      this.step = 'review';
    }
  }

  edit(): void {
    this.step = 'edit';
    setTimeout(() => this.quantityInput?.nativeElement.focus());
  }

  submit(): void {
    if (!this.canReview || !this.symbol || !this.accountId) {
      return;
    }
    this.step = 'submitting';
    this.submitError = null;
    this.orders.submitOrder({
      accountId: this.accountId,
      symbol: this.symbol,
      side: this.side,
      quantity: this.quantity!
    }).subscribe({
      next: response => {
        this.result = response;
        this.step = 'result';
        this.placed.emit(response);
      },
      error: err => {
        this.submitError = err.error?.message || err.error?.error || 'Your order could not be placed. Please try again.';
        this.step = 'review';
      }
    });
  }

  reset(): void {
    this.step = 'edit';
    this.quantity = null;
    this.result = null;
    this.submitError = null;
  }

  get fillPrice(): number | null {
    return this.result?.execution?.fillPrice ?? null;
  }
}
