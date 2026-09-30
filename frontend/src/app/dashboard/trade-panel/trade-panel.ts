import { ChangeDetectorRef, Component, ElementRef, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { TradeAccount } from '../models';
import { OrderService, OrderSide, OrderSubmissionResponse } from '../../services/order';
import { RollingNumberComponent } from '../../shared/rolling-number';
import { formatMoney } from '../../shared/format';
// Order placement confirmation: use the authenticated client's experience level.
import { ClientProfile, ProfileService } from '../../services/profile';

type Step = 'edit' | 'review' | 'submitting' | 'result' | 'unconfirmed';

/**
 * Buy/sell ticket for one symbol, modelled on Robinhood's: pick a side, enter whole shares, see
 * the live estimate against buying power (or shares held), review, then submit. Orders are market
 * orders - order-app fills them immediately at the live ask (buy) or bid (sell).
 */
@Component({
    selector: 'app-trade-panel',
    imports: [CommonModule, FormsModule, RollingNumberComponent],
    templateUrl: './trade-panel.html',
    styleUrl: './trade-panel.css'
})
export class TradePanelComponent implements OnChanges, OnInit {
  @Input() symbol: string | null = null;
  @Input() name: string | null = null;
  @Input() price: number | null = null;
  @Input() accounts: TradeAccount[] = [];
  @Output() placed = new EventEmitter<OrderSubmissionResponse>();
  /** The order may or may not have gone through; the account and activity should be reloaded. */
  @Output() unconfirmed = new EventEmitter<void>();

  @ViewChild('quantityInput') quantityInput?: ElementRef<HTMLInputElement>;
  // Order placement confirmation: the native dialog provides modal keyboard focus handling.
  @ViewChild('confirmationDialog') confirmationDialog?: ElementRef<HTMLDialogElement>;
  confirmationOpen = false;
  private experienceLevel: ClientProfile['experienceLevel'] | null = null;
  private readonly profileService = inject(ProfileService);

  ngOnInit(): void {
    this.profileService.getMe().subscribe({
      next: profile => this.experienceLevel = profile.experienceLevel,
      error: () => this.experienceLevel = null
    });
  }

  // Order placement confirmation: unknown experience requires confirmation too.
  get requiresConfirmation(): boolean {
    return this.experienceLevel == null || this.experienceLevel === 'NOVICE'
      || (this.estimate ?? 0) >= 25_000;
  }

  side: OrderSide = 'BUY';
  accountId: string | null = null;
  quantity: number | null = null;
  step: Step = 'edit';
  result: OrderSubmissionResponse | null = null;
  submitError: string | null = null;

  money = formatMoney;

  private readonly orders = inject(OrderService);
  // Notify Angular when a parent opens the ticket from an asynchronous callback.
  private readonly changeDetector = inject(ChangeDetectorRef);

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['accounts'] && (!this.accountId || !this.accounts.some(a => a.accountId === this.accountId))) {
      // Order placement confirmation: don't confirm a different account than the one reviewed.
      this.cancelConfirmation();
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
    this.changeDetector.markForCheck();
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
    // Order placement confirmation: editing invalidates the previous prompt.
    this.cancelConfirmation();
    this.step = 'edit';
    setTimeout(() => this.quantityInput?.nativeElement.focus());
  }

  submit(): void {
    // Order placement confirmation: clicking Submit opens the prompt before any API call.
    if (this.step !== 'review' || this.confirmationOpen || !this.canReview) {
      return;
    }
    if (this.requiresConfirmation) {
      this.submitError = null;
      this.confirmationOpen = true;
      this.confirmationDialog?.nativeElement.showModal();
      return;
    }
    this.placeOrder();
  }

  // Order placement confirmation: only the dialog's affirmative action reaches submission.
  confirmOrder(): void {
    if (!this.confirmationOpen || this.step !== 'review') {
      return;
    }
    this.cancelConfirmation();
    this.placeOrder();
  }

  // Order placement confirmation: Go back and Escape close the prompt without an API call.
  cancelConfirmation(): void {
    this.confirmationOpen = false;
    this.confirmationDialog?.nativeElement.close();
  }

  // Order placement confirmation: revalidate live buying power and prevent repeat submissions.
  private placeOrder(): void {
    if (this.step !== 'review' || !this.canReview || !this.symbol || !this.accountId) {
      this.submitError = this.blocker;
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
      error: (err: HttpErrorResponse) => {
        if (this.outcomeUnknown(err)) {
          // It may still complete (order-app finishes interrupted trades), so don't invite a
          // second submission that would buy or sell twice.
          this.step = 'unconfirmed';
          this.unconfirmed.emit();
          return;
        }
        this.submitError = err.error?.message || err.error?.error || 'Your order could not be placed. Please try again.';
        this.step = 'review';
      }
    });
  }

  /**
   * Whether a failed submission may still have placed the order: no answer came back (status 0,
   * e.g. the connection dropped) or the server failed part-way (5xx). A 4xx means the order was
   * refused before anything was booked.
   */
  private outcomeUnknown(err: HttpErrorResponse): boolean {
    return err.status === 0 || err.status >= 500;
  }

  reset(): void {
    // Order placement confirmation: a new ticket needs a new confirmation.
    this.cancelConfirmation();
    this.step = 'edit';
    this.quantity = null;
    this.result = null;
    this.submitError = null;
  }

  get fillPrice(): number | null {
    return this.result?.execution?.fillPrice ?? null;
  }
}
