import { Component, ElementRef, EventEmitter, OnChanges, OnInit, Output, SimpleChanges, ViewChild, computed, inject, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { TradeAccount } from '../models';
import { OrderService, OrderSide, OrderSubmissionResponse } from '../../services/order';
import { RollingNumberComponent } from '../../shared/rolling-number';
import { formatMoney } from '../../shared/format';
import { MarketDataService } from '../../services/market-data';
import { ProtectionPickerComponent } from '../../shared/protection-picker';
import { formatProtection, protectionBand } from '../../shared/price-protection';
// Order placement confirmation: use the authenticated client's experience level.
import { ClientProfile, ProfileService } from '../../services/profile';

type Step = 'edit' | 'review' | 'submitting' | 'result' | 'unconfirmed';

/** How order-app starts the reason for rejecting an order the price moved too far on. */
const PRICE_MOVED_REASON = 'Price moved';

/**
 * Buy/sell ticket for one symbol, modelled on Robinhood's: pick a side, enter whole shares, see
 * the live estimate against buying power (or shares held), review, then submit. Orders are market
 * orders - order-app fills them immediately at the live ask (buy) or bid (sell).
 *
 * Price protection: reviewing captures the price the order is quoted at (the ask for a buy, the bid
 * for a sell), and order-app rejects the order if it would fill further than the account's saved
 * protection - or the one picked on the ticket - from that price.
 *
 * The app is zoneless, so all template state lives in signals: an HTTP callback or a parent's
 * timer that sets a plain field wouldn't schedule a refresh.
 */
@Component({
    selector: 'app-trade-panel',
    imports: [CommonModule, FormsModule, RouterLink, RollingNumberComponent, ProtectionPickerComponent],
    templateUrl: './trade-panel.html',
    styleUrl: './trade-panel.css'
})
export class TradePanelComponent implements OnChanges, OnInit {
  readonly symbol = input<string | null>(null);
  readonly name = input<string | null>(null);
  readonly price = input<number | null>(null);
  readonly accounts = input<TradeAccount[]>([]);
  /** Account to trade in by default, e.g. the one the dashboard is scoped to. */
  readonly preferredAccountId = input<string | null>(null);
  @Output() placed = new EventEmitter<OrderSubmissionResponse>();
  /** The order may or may not have gone through; the account and activity should be reloaded. */
  @Output() unconfirmed = new EventEmitter<void>();

  @ViewChild('quantityInput') quantityInput?: ElementRef<HTMLInputElement>;
  // Order placement confirmation: the native dialog provides modal keyboard focus handling.
  @ViewChild('confirmationDialog') confirmationDialog?: ElementRef<HTMLDialogElement>;
  readonly confirmationOpen = signal(false);
  private readonly experienceLevel = signal<ClientProfile['experienceLevel'] | null>(null);
  private readonly profileService = inject(ProfileService);

  ngOnInit(): void {
    this.profileService.getMe().subscribe({
      next: profile => this.experienceLevel.set(profile.experienceLevel),
      error: () => this.experienceLevel.set(null)
    });
  }

  // Order placement confirmation: unknown experience requires confirmation too.
  readonly requiresConfirmation = computed(() => {
    const level = this.experienceLevel();
    return level == null || level === 'NOVICE' || (this.estimate() ?? 0) >= 25_000;
  });

  readonly side = signal<OrderSide>('BUY');
  readonly accountId = signal<string | null>(null);
  readonly quantity = signal<number | null>(null);
  readonly step = signal<Step>('edit');
  readonly result = signal<OrderSubmissionResponse | null>(null);
  readonly submitError = signal<string | null>(null);

  money = formatMoney;

  private readonly orders = inject(OrderService);
  private readonly marketData = inject(MarketDataService);

  // ---- Price protection ----
  /** Protection picked on the ticket for this order only, kept with the account it was picked for. */
  private readonly protectionOverride = signal<{ accountId: string; value: number | null } | null>(null);
  readonly protectionOpen = signal(false);
  /** The price the order was reviewed at; order-app measures the fill's move from it. */
  readonly quotedPrice = signal<number | null>(null);
  private quoteRequest?: Subscription;

  readonly savedProtection = computed(() => this.account()?.maxSlippagePercent ?? null);

  /** The protection this order is placed with: the ticket's pick, else the account's saved one. */
  readonly protection = computed(() => {
    const override = this.protectionOverride();
    return override && override.accountId === this.accountId() ? override.value : this.savedProtection();
  });

  /** "±0.5%" or "Off". */
  readonly protectionLabel = computed(() => {
    const protection = this.protection();
    return protection == null ? 'Off' : `±${formatProtection(protection)}`;
  });

  /** "±0.5% · $99.50–$100.50": the prices the order may fill between. */
  readonly protectionSummary = computed(() => {
    const protection = this.protection();
    const quoted = this.quotedPrice() ?? this.price();
    if (protection == null || quoted == null) {
      return this.protectionLabel();
    }
    const { low, high } = protectionBand(quoted, protection);
    return `${this.protectionLabel()} · ${formatMoney(low)}–${formatMoney(high)}`;
  });

  setProtection(value: number | null): void {
    const accountId = this.accountId();
    if (accountId) {
      this.protectionOverride.set({ accountId, value });
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    const accounts = this.accounts();
    if (changes['accounts'] && (!this.accountId() || !accounts.some(a => a.accountId === this.accountId()))) {
      // Order placement confirmation: don't confirm a different account than the one reviewed.
      this.cancelConfirmation();
      this.accountId.set(accounts[0]?.accountId ?? null);
    }
    // Applied when the preference changes or accounts first arrive, not on every refresh, so a
    // manual pick in the ticket sticks.
    const accountsArrived = changes['accounts'] && !changes['accounts'].previousValue?.length;
    const preferred = this.preferredAccountId();
    if ((changes['preferredAccountId'] || accountsArrived) && this.step() !== 'submitting'
        && preferred && preferred !== this.accountId()
        && accounts.some(a => a.accountId === preferred)) {
      this.cancelConfirmation();
      this.accountId.set(preferred);
    }
    if (changes['symbol'] && !changes['symbol'].firstChange && this.step() !== 'submitting') {
      this.reset();
    }
  }

  /** Opens the ticket on a side, e.g. from a position row's Buy/Sell button. */
  open(side: OrderSide): void {
    if (this.step() === 'submitting') {
      return;
    }
    this.side.set(side);
    this.reset();
    setTimeout(() => this.quantityInput?.nativeElement.focus());
  }

  setSide(side: OrderSide): void {
    if (this.step() === 'edit') {
      this.side.set(side);
    }
  }

  readonly account = computed(() => this.accounts().find(a => a.accountId === this.accountId()));

  readonly sharesHeld = computed(() => {
    const symbol = this.symbol();
    return symbol ? this.account()?.shares[symbol] ?? 0 : 0;
  });

  readonly validQuantity = computed(() => {
    const quantity = this.quantity();
    return quantity != null && Number.isInteger(quantity) && quantity >= 1;
  });

  readonly estimate = computed(() => {
    const price = this.price();
    return price != null && this.validQuantity() ? price * this.quantity()! : null;
  });

  /** Why the order can't be reviewed yet, or null if it can. */
  readonly blocker = computed(() => {
    const account = this.account();
    const quantity = this.quantity();
    const sharesHeld = this.sharesHeld();
    if (!this.symbol()) {
      return 'Choose a symbol to trade';
    }
    if (!account) {
      return 'No active account to trade from';
    }
    if (this.price() == null) {
      return 'Waiting for a live price…';
    }
    if (quantity == null) {
      return null;
    }
    if (!this.validQuantity()) {
      return 'Enter a whole number of shares';
    }
    if (this.side() === 'BUY' && this.estimate()! > account.buyingPower) {
      return `Not enough buying power (${formatMoney(account.buyingPower)} available)`;
    }
    if (this.side() === 'SELL' && quantity > sharesHeld) {
      return sharesHeld === 0
        ? `You don't own any ${this.symbol()} in this account`
        : `You only have ${sharesHeld} ${sharesHeld === 1 ? 'share' : 'shares'} to sell`;
    }
    return null;
  });

  readonly canReview = computed(() => this.validQuantity() && this.blocker() === null);

  sellAll(): void {
    this.quantity.set(this.sharesHeld() || null);
  }

  adjust(delta: number): void {
    this.quantity.set(Math.max(1, (this.validQuantity() ? this.quantity()! : 0) + delta));
  }

  review(): void {
    if (this.canReview()) {
      this.step.set('review');
      this.quoteForReview();
    }
  }

  /**
   * Captures the price the order is reviewed at. The streamed price stands in at once, so Submit
   * never waits; the quote's ask (buy) or bid (sell) - what the order actually fills at - replaces
   * it when it arrives.
   */
  private quoteForReview(): void {
    const symbol = this.symbol();
    const side = this.side();
    this.quotedPrice.set(this.price());
    this.quoteRequest?.unsubscribe();
    if (!symbol) {
      return;
    }
    this.quoteRequest = this.marketData.getQuote(symbol).subscribe({
      next: quote => {
        const price = Number(side === 'BUY' ? quote.askPrice : quote.bidPrice);
        if (this.step() === 'review' && this.symbol() === symbol && price > 0) {
          this.quotedPrice.set(price);
        }
      },
      // The streamed price is within the spread of the quote, so it serves.
      error: () => {}
    });
  }

  /** Whether the order was rejected because the price moved beyond its protection. */
  readonly priceMoved = computed(() => {
    const result = this.result();
    return result?.status === 'REJECTED' && !!result.rejectionReason?.startsWith(PRICE_MOVED_REASON);
  });

  /** After a price-move rejection: the same order, reviewed again at the new price. */
  reviewAtNewPrice(): void {
    this.result.set(null);
    this.step.set('edit');
    this.review();
  }

  edit(): void {
    // Order placement confirmation: editing invalidates the previous prompt.
    this.cancelConfirmation();
    this.step.set('edit');
    setTimeout(() => this.quantityInput?.nativeElement.focus());
  }

  submit(): void {
    // Order placement confirmation: clicking Submit opens the prompt before any API call.
    if (this.step() !== 'review' || this.confirmationOpen() || !this.canReview()) {
      return;
    }
    if (this.requiresConfirmation()) {
      this.submitError.set(null);
      this.confirmationOpen.set(true);
      this.confirmationDialog?.nativeElement.showModal();
      return;
    }
    this.placeOrder();
  }

  // Order placement confirmation: only the dialog's affirmative action reaches submission.
  confirmOrder(): void {
    if (!this.confirmationOpen() || this.step() !== 'review') {
      return;
    }
    this.cancelConfirmation();
    this.placeOrder();
  }

  // Order placement confirmation: Go back and Escape close the prompt without an API call.
  cancelConfirmation(): void {
    this.confirmationOpen.set(false);
    this.confirmationDialog?.nativeElement.close();
  }

  // Order placement confirmation: revalidate live buying power and prevent repeat submissions.
  private placeOrder(): void {
    const symbol = this.symbol();
    const accountId = this.accountId();
    if (this.step() !== 'review' || !this.canReview() || !symbol || !accountId) {
      this.submitError.set(this.blocker());
      return;
    }
    this.step.set('submitting');
    this.submitError.set(null);
    const quotedPrice = this.quotedPrice();
    const protection = this.protection();
    this.orders.submitOrder({
      accountId,
      symbol,
      side: this.side(),
      quantity: this.quantity()!,
      ...(quotedPrice != null ? { quotedPrice } : {}),
      ...(protection != null ? { maxSlippagePercent: protection } : {})
    }).subscribe({
      next: response => {
        this.result.set(response);
        this.step.set('result');
        this.placed.emit(response);
      },
      error: (err: HttpErrorResponse) => {
        if (this.outcomeUnknown(err)) {
          // It may still complete (order-app finishes interrupted trades), so don't invite a
          // second submission that would buy or sell twice.
          this.step.set('unconfirmed');
          this.unconfirmed.emit();
          return;
        }
        this.submitError.set(err.error?.message || err.error?.error || 'Your order could not be placed. Please try again.');
        this.step.set('review');
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
    this.step.set('edit');
    this.quantity.set(null);
    this.result.set(null);
    this.submitError.set(null);
    this.protectionOverride.set(null);
    this.protectionOpen.set(false);
    this.quoteRequest?.unsubscribe();
    this.quotedPrice.set(null);
  }

  readonly fillPrice = computed(() => this.result()?.execution?.fillPrice ?? null);
}
