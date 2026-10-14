import { Component, ElementRef, EventEmitter, OnChanges, OnInit, Output, SimpleChanges, ViewChild, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { TradeAccount } from '../models';
import { OrderService, OrderSide, OrderSubmissionRequest, OrderSubmissionResponse, OrderType, TimeInForce } from '../../services/order';
import { RollingNumberComponent } from '../../shared/rolling-number';
import { formatMoney } from '../../shared/format';
import { Candle, MarketDataService } from '../../services/market-data';
import { HIT_RATE_DAYS, hitRateDisclaimer, hitRateHint } from '../../shared/price-hit-rate';
import { concentrationWarning, orderImpact } from '../../shared/order-impact';
import { MAX_TEMPLATES, OrderTemplate, OrderTemplateService } from '../../services/order-templates';
import { applyTemplate, bracketPrices, suggestedTemplateName, templateFromTicket } from '../../shared/order-templates';
import { STOP_LOSS_PERCENTS, TAKE_PROFIT_PERCENTS, bracketProblem, riskReward, riskRewardLabel } from '../../shared/bracket';
import { ProtectionPickerComponent } from '../../shared/protection-picker';
import { formatProtection, protectionBand } from '../../shared/price-protection';
import {
  ORDER_TYPES, QuoteOffset, describeOrder, distanceLabel, distanceTo, orderTypeHint, orderTypeLabel,
  reservePricePerShare, sentenceText, timeInForceLabel, toCents, trailText
} from '../../shared/order-types';
import { ChartLine, OrderChartComponent } from '../order-chart/order-chart';
// Order placement confirmation: use the authenticated client's experience level.
import { ClientProfile, ProfileService } from '../../services/profile';

type Step = 'edit' | 'review' | 'submitting' | 'result' | 'unconfirmed';

/** How order-app starts the reason for rejecting an order the price moved too far on. */
const PRICE_MOVED_REASON = 'Price moved';

/** A limit or stop further than this from the market may never be reached, so the ticket says so. */
const FAR_FROM_MARKET_PERCENT = 25;

/** A one-tap price for a limit or stop field. */
export interface PriceChip {
  label: string;
  value: number;
}

/**
 * Buy/sell ticket for one symbol, modelled on Robinhood's: pick a side and an order type, enter
 * whole shares (and a limit or stop price), see the live estimate against buying power (or shares
 * held), review, then submit.
 *
 * Order types: market orders fill immediately at the live ask (buy) or bid (sell). Limit, stop and
 * stop limit orders wait in order-app until the market reaches their price, holding back the cash
 * or shares they may need; the ticket explains each one as a plain-English sentence that updates
 * as it's edited, offers one-tap prices, and warns before an order that would fill at once or may
 * never fill.
 *
 * Bid and ask: the live price is the last trade, but a buy fills at the ask and a sell at the bid,
 * a little above and below it. The ticket shows both, and measures limit and stop prices, their
 * suggestions and its checks from the side's own price, so an order reads as reached exactly when
 * order-app would fill it.
 *
 * Price protection (market orders only): reviewing captures the price the order is quoted at (the
 * ask for a buy, the bid for a sell), and order-app rejects the order if it would fill further than
 * the account's saved protection - or the one picked on the ticket - from that price.
 *
 * The app is zoneless, so all template state lives in signals: an HTTP callback or a parent's
 * timer that sets a plain field wouldn't schedule a refresh.
 */
@Component({
    selector: 'app-trade-panel',
    imports: [CommonModule, FormsModule, RouterLink, RollingNumberComponent, ProtectionPickerComponent, OrderChartComponent],
    templateUrl: './trade-panel.html',
    styleUrls: ['./trade-panel.css', './order-type.css']
})
export class TradePanelComponent implements OnChanges, OnInit {
  readonly symbol = input<string | null>(null);
  readonly name = input<string | null>(null);
  /** The live last-trade price. */
  readonly price = input<number | null>(null);
  /** How far the symbol's ask and bid sit from its last trade; null until its quote is read. */
  readonly quoteOffset = input<QuoteOffset | null>(null);
  /** The symbol's recent closes, drawn behind the price lines of a limit or stop order. */
  readonly priceHistory = input<number[]>([]);
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
    this.templateService.list().subscribe({
      next: templates => this.templates.set(templates),
      // Templates are a shortcut: without them the ticket works as before.
      error: () => this.templates.set([])
    });
  }

  // ---- Templates: saved ticket setups, applied in one tap ----
  private readonly templateService = inject(OrderTemplateService);
  readonly templates = signal<OrderTemplate[]>([]);
  readonly templateName = signal('');
  readonly savingTemplate = signal(false);
  readonly templateFormOpen = signal(false);
  readonly templateMessage = signal<{ text: string; tone: 'ok' | 'error' } | null>(null);
  readonly templatesFull = computed(() => this.templates().length >= MAX_TEMPLATES);

  /** Sets the ticket up from a template, with prices worked out from the market right now. */
  applyTemplate(template: OrderTemplate): void {
    const price = template.side === 'BUY' ? this.ask() ?? this.price() : this.bid() ?? this.price();
    if (this.step() !== 'edit' || price == null || !(price > 0)) {
      return;
    }
    const applied = applyTemplate(template, price);
    this.side.set(applied.side);
    this.orderType.set(applied.orderType);
    this.timeInForce.set(applied.timeInForce);
    // The template's prices stand: the ticket's own suggestions must not replace them.
    this.pricesTouched.set(true);
    this.limitPrice.set(applied.limitPrice);
    this.stopPrice.set(applied.stopPrice);
    if (applied.trailPercent != null) {
      this.trailUnit.set('PERCENT');
      this.trailValue.set(applied.trailPercent);
    } else if (applied.orderType !== 'TRAILING_STOP') {
      this.trailValue.set(null);
    } else {
      // A trailing-stop template without a percentage keeps the current trail settings.
    }
    if (applied.side === 'BUY' && applied.takeProfitPercent != null && applied.stopLossPercent != null) {
      const entry = this.estimatePrice();
      if (entry != null && entry > 0) {
        const exits = bracketPrices(entry, applied.takeProfitPercent, applied.stopLossPercent);
        this.bracketTouched.set(true);
        this.takeProfit.set(exits.takeProfit);
        this.stopLoss.set(exits.stopLoss);
        this.bracketOn.set(true);
      }
    } else {
      this.bracketOn.set(false);
    }
    this.templateMessage.set(null);
  }

  /** The ticket as a template to save, or null while a price it needs is missing. */
  private ticketTemplate(name: string) {
    const price = this.sidePrice();
    if (price == null) {
      return null;
    }
    return templateFromTicket(name, {
      side: this.side(), orderType: this.orderType(), timeInForce: this.timeInForce(), sidePrice: price,
      limitPrice: this.limitPrice(), stopPrice: this.stopPrice(),
      trailUnit: this.trailUnit(), trailValue: this.trailValue(),
      entryPrice: this.estimatePrice(),
      takeProfit: this.bracketActive() ? this.takeProfit() : null,
      stopLoss: this.bracketActive() ? this.stopLoss() : null
    });
  }

  /** Opens the "Save as template" form on the review step, with a name to start from. */
  openTemplateForm(): void {
    const draft = this.ticketTemplate('');
    this.templateName.set(draft ? suggestedTemplateName(draft) : '');
    this.templateMessage.set(null);
    this.templateFormOpen.set(true);
  }

  saveTemplate(): void {
    const name = this.templateName().trim();
    const template = this.ticketTemplate(name);
    if (!name || !template || this.savingTemplate()) {
      this.templateMessage.set({ text: name ? 'This setup can\u2019t be saved as a template yet.' : 'Give the template a name.', tone: 'error' });
      return;
    }
    this.savingTemplate.set(true);
    this.templateService.create(template).subscribe({
      next: saved => {
        this.templates.update(list => [saved, ...list]);
        this.savingTemplate.set(false);
        this.templateFormOpen.set(false);
        this.templateMessage.set({ text: `Saved \u201c${saved.name}\u201d.`, tone: 'ok' });
      },
      error: (err: HttpErrorResponse) => {
        this.savingTemplate.set(false);
        this.templateMessage.set({ text: err.error?.message || 'The template could not be saved.', tone: 'error' });
      }
    });
  }

  deleteTemplate(template: OrderTemplate): void {
    this.templateService.delete(template.templateId).subscribe({
      next: () => this.templates.update(list => list.filter(t => t.templateId !== template.templateId)),
      error: () => this.templateMessage.set({ text: 'The template could not be deleted.', tone: 'error' })
    });
  }

  // Order placement confirmation: unknown experience requires confirmation too.
  readonly requiresConfirmation = computed(() => {
    const level = this.experienceLevel();
    return level == null || level === 'NOVICE' || (this.estimate() ?? 0) >= 25_000;
  });

  /** Novices get a short explanation of the order type in the confirmation. */
  readonly novice = computed(() => this.experienceLevel() == null || this.experienceLevel() === 'NOVICE');

  readonly side = signal<OrderSide>('BUY');
  readonly accountId = signal<string | null>(null);
  readonly quantity = signal<number | null>(null);
  readonly step = signal<Step>('edit');
  readonly result = signal<OrderSubmissionResponse | null>(null);
  readonly submitError = signal<string | null>(null);

  money = formatMoney;
  typeLabel = orderTypeLabel;
  tifLabel = timeInForceLabel;
  readonly orderTypes = ORDER_TYPES;

  private readonly orders = inject(OrderService);
  private readonly marketData = inject(MarketDataService);

  // ---- Order type ----
  readonly orderType = signal<OrderType>('MARKET');
  readonly limitPrice = signal<number | null>(null);
  readonly stopPrice = signal<number | null>(null);
  readonly timeInForce = signal<TimeInForce>('DAY');
  /** A trailing stop's trail, in percent of the best price or in dollars. */
  readonly trailUnit = signal<'PERCENT' | 'AMOUNT'>('PERCENT');
  readonly trailValue = signal<number | null>(null);
  /** Whether the client has set a price themselves; until then the ticket suggests one. */
  private readonly pricesTouched = signal(false);

  // ---- Bracket: a take-profit and stop-loss that open as sells once a buy fills ----
  readonly bracketOn = signal(false);
  readonly takeProfit = signal<number | null>(null);
  readonly stopLoss = signal<number | null>(null);
  private readonly bracketTouched = signal(false);

  /** The live ask and bid: the last trade moved by the latest quote's offsets. */
  readonly ask = computed(() => {
    const price = this.price();
    const offset = this.quoteOffset();
    return price == null || !offset ? null : price + offset.ask;
  });
  readonly bid = computed(() => {
    const price = this.price();
    const offset = this.quoteOffset();
    return price == null || !offset ? null : price + offset.bid;
  });

  /** The price this side fills at - the ask for a buy, the bid for a sell - or the last trade until the quote is in. */
  readonly sidePrice = computed(() => (this.side() === 'BUY' ? this.ask() : this.bid()) ?? this.price());

  /** "the ask" / "the bid", or "the current price" while there's no quote. */
  readonly sideReference = computed(() => this.quoteOffset() == null
    ? 'the current price'
    : this.side() === 'BUY' ? 'the ask' : 'the bid');

  readonly isMarket = computed(() => this.orderType() === 'MARKET');
  readonly hasLimit = computed(() => this.orderType() === 'LIMIT' || this.orderType() === 'STOP_LIMIT');
  readonly hasStop = computed(() => this.orderType() === 'STOP' || this.orderType() === 'STOP_LIMIT');
  readonly hasTrail = computed(() => this.orderType() === 'TRAILING_STOP');
  readonly typeHint = computed(() => orderTypeHint(this.orderType(), this.side()));

  constructor() {
    // Suggest prices for a new limit or stop once there's a live price to start from: a limit 1%
    // better than the market (a limit at the market would just fill now), a stop 5% away in the
    // direction it protects against. The client's own prices win.
    effect(() => {
      const price = this.sidePrice();
      const type = this.orderType();
      const side = this.side();
      if (price == null || type === 'MARKET' || this.pricesTouched()) {
        return;
      }
      untracked(() => {
        const stop = toCents(side === 'SELL' ? price * 0.95 : price * 1.05);
        if (this.hasStop() && this.stopPrice() == null) {
          this.stopPrice.set(stop);
        }
        if (this.hasLimit() && this.limitPrice() == null) {
          this.limitPrice.set(type === 'STOP_LIMIT'
            ? this.stopPrice() ?? stop
            : toCents(side === 'BUY' ? price * 0.99 : price * 1.01));
        }
      });
    });

    // The last month of daily candles for the hit-rate hint, fetched once per symbol and only
    // while an order that waits for a price is being set up.
    effect(() => {
      const symbol = this.symbol();
      if (!symbol || this.orderType() === 'MARKET') {
        return;
      }
      untracked(() => this.loadDayCandles(symbol));
    });
  }

  // ---- Hit rate: how often the market reached this price lately ----
  private readonly dayCandles = signal<{ symbol: string; candles: Candle[] } | null>(null);
  private candleRequest?: Subscription;

  private loadDayCandles(symbol: string): void {
    if (this.dayCandles()?.symbol === symbol) {
      return;
    }
    this.candleRequest?.unsubscribe();
    const to = new Date();
    const from = new Date(to.getTime() - (HIT_RATE_DAYS + 2) * 86_400_000);
    this.candleRequest = this.marketData.getHistory(symbol, from, to, 86400, HIT_RATE_DAYS + 2).subscribe({
      next: candles => this.dayCandles.set({ symbol, candles }),
      // A hint is a nicety: without history the ticket just shows none.
      error: () => this.dayCandles.set({ symbol, candles: [] })
    });
  }

  /** "AAPL traded at or below $180.00 on 9 of the last 30 days.", or null when there is none to give. */
  readonly hitRate = computed(() => {
    const symbol = this.symbol();
    const loaded = this.dayCandles();
    if (!symbol || !loaded || loaded.symbol !== symbol || this.isMarket()) {
      return null;
    }
    return hitRateHint({
      symbol, side: this.side(), type: this.orderType(),
      limitPrice: this.limitPrice(), stopPrice: this.stopPrice(),
      trailPercent: this.trailUnit() === 'PERCENT' ? this.trailValue() : null,
      trailAmount: this.trailUnit() === 'AMOUNT' ? this.trailValue() : null
    }, loaded.candles);
  });

  readonly hitRateNote = computed(() => {
    const hint = this.hitRate();
    return hint ? hitRateDisclaimer(hint) : null;
  });

  // ---- Impact: what the order would do to the position and the account ----
  readonly impact = computed(() => {
    const account = this.account();
    const price = this.estimatePrice();
    const market = this.price();
    if (!account || !this.validQuantity()) {
      return null;
    }
    if (price == null || !(price > 0) || market == null) {
      return null;
    }
    return orderImpact({
      side: this.side(), quantity: this.quantity()!, price, marketPrice: market,
      sharesOwned: this.sharesOwned(), averageCost: this.averageCost(),
      buyingPower: account.buyingPower, totalValue: account.totalValue ?? null,
      stopLossPrice: this.bracketActive() ? this.stopLoss() : null
    });
  });

  readonly concentrationWarning = computed(() => {
    const impact = this.impact();
    const symbol = this.symbol();
    return impact?.concentrated && symbol ? concentrationWarning(symbol, impact.portfolioShare!) : null;
  });

  setOrderType(type: OrderType): void {
    if (this.step() !== 'edit' || type === this.orderType()) {
      return;
    }
    this.orderType.set(type);
    if (!this.pricesTouched()) {
      // Fresh suggestions for the new type (see the constructor's effect).
      this.limitPrice.set(null);
      this.stopPrice.set(null);
    }
    if (type === 'TRAILING_STOP' && this.trailValue() == null) {
      this.trailValue.set(this.defaultTrail());
    }
  }

  /** 5% to start with, or the dollar equivalent. */
  private defaultTrail(): number | null {
    if (this.trailUnit() === 'PERCENT') {
      return 5;
    }
    const price = this.sidePrice();
    return price == null ? null : toCents(price * 0.05);
  }

  setTrailValue(value: number | null): void {
    this.trailValue.set(value);
  }

  /** Switches between percent and dollars, keeping the same trail at the current price. */
  setTrailUnit(unit: 'PERCENT' | 'AMOUNT'): void {
    const price = this.sidePrice();
    const value = this.trailValue();
    if (unit === this.trailUnit()) {
      return;
    }
    this.trailUnit.set(unit);
    if (value == null || price == null || !(price > 0)) {
      this.trailValue.set(this.defaultTrail());
    } else {
      this.trailValue.set(unit === 'AMOUNT' ? toCents(price * value / 100) : Math.round(value / price * 1000) / 10);
    }
  }

  setLimitPrice(value: number | null): void {
    this.pricesTouched.set(true);
    this.limitPrice.set(value);
  }

  setStopPrice(value: number | null): void {
    this.pricesTouched.set(true);
    this.stopPrice.set(value);
  }

  setTimeInForce(value: TimeInForce): void {
    if (this.step() === 'edit') {
      this.timeInForce.set(value);
    }
  }

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
    const preferenceChanged = changes['preferredAccountId'] || accountsArrived;
    const canSwitch = !!preferred && preferred !== this.accountId()
        && accounts.some(a => a.accountId === preferred);
    if (preferenceChanged && this.step() !== 'submitting' && canSwitch) {
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
    if (this.step() === 'edit' && side !== this.side()) {
      this.side.set(side);
      if (!this.pricesTouched()) {
        // A stop protects the other way now; suggest again.
        this.limitPrice.set(null);
        this.stopPrice.set(null);
      }
      if (side === 'SELL') {
        // The app has no short selling, so a sell has nothing to bracket.
        this.bracketOn.set(false);
      }
    }
  }

  readonly account = computed(() => this.accounts().find(a => a.accountId === this.accountId()));

  /** Shares owned in this account. */
  readonly sharesOwned = computed(() => {
    const symbol = this.symbol();
    return symbol ? this.account()?.shares[symbol] ?? 0 : 0;
  });

  /** Shares already promised to open sell orders. */
  readonly sharesOnHold = computed(() => {
    const symbol = this.symbol();
    return symbol ? this.account()?.sharesOnHold?.[symbol] ?? 0 : 0;
  });

  /** Shares free to sell: owned, less those open sell orders hold. */
  readonly sharesHeld = computed(() => Math.max(0, this.sharesOwned() - this.sharesOnHold()));

  readonly averageCost = computed(() => {
    const symbol = this.symbol();
    return symbol ? this.account()?.averageCost?.[symbol] ?? null : null;
  });

  readonly validQuantity = computed(() => {
    const quantity = this.quantity();
    return quantity != null && Number.isInteger(quantity) && quantity >= 1;
  });

  private static validPrice(value: number | null): boolean {
    return value != null && Number.isFinite(value) && value > 0;
  }

  /** The price per share the estimate is figured at: the market's, or the order's own limit or stop. */
  readonly estimatePrice = computed(() => {
    switch (this.orderType()) {
      case 'MARKET': return this.sidePrice();
      case 'STOP': return this.stopPrice();
      case 'TRAILING_STOP': return this.initialStop();
      default: return this.limitPrice();
    }
  });

  /** Where a trailing stop starts: the trail away from the price this side fills at. */
  readonly initialStop = computed(() => {
    const price = this.sidePrice();
    const trail = this.trailValue();
    if (!this.hasTrail() || price == null || trail == null || !(trail > 0)) {
      return null;
    }
    const distance = this.trailUnit() === 'PERCENT' ? price * trail / 100 : trail;
    return toCents(this.side() === 'SELL' ? price - distance : price + distance);
  });

  readonly estimate = computed(() => {
    const price = this.estimatePrice();
    return price != null && this.validQuantity() ? price * this.quantity()! : null;
  });

  /** What a buy needs in buying power: the estimate, or what an open order holds back. */
  readonly costToHold = computed(() => {
    if (this.isMarket()) {
      return this.estimate();
    }
    const perShare = reservePricePerShare(this.orderType(), this.limitPrice(),
      this.hasTrail() ? this.initialStop() : this.stopPrice());
    return perShare != null && this.validQuantity() ? toCents(perShare * this.quantity()!) : null;
  });

  /** A bracket is only offered on a buy: the app has no short selling. */
  readonly bracketAvailable = computed(() => this.side() === 'BUY');

  /** Whether the bracket is attached to this order. */
  readonly bracketActive = computed(() => this.bracketOn() && this.bracketAvailable());

  /** One-tap take-profit and stop-loss prices, as percent from the price the buy enters at. */
  readonly takeProfitChips = computed<PriceChip[]>(() => this.bracketChips(TAKE_PROFIT_PERCENTS, 1));
  readonly stopLossChips = computed<PriceChip[]>(() => this.bracketChips(STOP_LOSS_PERCENTS, -1));

  private bracketChips(percents: number[], direction: 1 | -1): PriceChip[] {
    const entry = this.estimatePrice();
    if (entry == null || !(entry > 0)) {
      return [];
    }
    return percents.map(percent => ({
      label: `${direction > 0 ? '+' : '−'}${percent}%`,
      value: toCents(entry * (1 + direction * percent / 100))
    }));
  }

  /** "Risk $120.00 · Reward $300.00 · 1 : 2.5" for the bracket as set. */
  readonly riskRewardText = computed(() => {
    const entry = this.estimatePrice();
    const take = this.takeProfit();
    const stop = this.stopLoss();
    if (!this.bracketActive() || !this.validQuantity()) {
      return null;
    }
    if (entry == null || take == null || stop == null) {
      return null;
    }
    const value = riskReward(entry, take, stop, this.quantity()!);
    return value ? riskRewardLabel(value) : null;
  });

  readonly bracketBlocker = computed(() =>
    this.bracketActive() ? bracketProblem(this.estimatePrice(), this.takeProfit(), this.stopLoss()) : null);

  /** Turns the bracket on or off, starting from +10% / −5% of the entry price. */
  setBracketOn(on: boolean): void {
    if (this.step() !== 'edit') {
      return;
    }
    this.bracketOn.set(on);
    const entry = this.estimatePrice();
    if (on && !this.bracketTouched() && entry != null && entry > 0) {
      this.takeProfit.set(toCents(entry * 1.1));
      this.stopLoss.set(toCents(entry * 0.95));
    }
  }

  setTakeProfit(value: number | null): void {
    this.bracketTouched.set(true);
    this.takeProfit.set(value);
  }

  setStopLoss(value: number | null): void {
    this.bracketTouched.set(true);
    this.stopLoss.set(value);
  }

  /** Why the order can't be reviewed yet, or null if it can. */
  readonly blocker = computed(() => {
    const account = this.account();
    const quantity = this.quantity();
    const sharesHeld = this.sharesHeld();
    const price = this.sidePrice();
    if (!this.symbol()) {
      return 'Choose a symbol to trade';
    }
    if (!account) {
      return 'No active account to trade from';
    }
    if (price == null) {
      return 'Waiting for a live price…';
    }
    if (quantity == null) {
      return null;
    }
    if (!this.validQuantity()) {
      return 'Enter a whole number of shares';
    }
    if (this.hasTrail()) {
      const trail = this.trailValue();
      if (trail == null || !(trail > 0)) {
        return 'Enter how far to trail';
      }
      if (this.trailUnit() === 'PERCENT' && (trail < 0.1 || trail > 50)) {
        return 'Trail by between 0.1% and 50%';
      }
      const stop = this.initialStop();
      if (stop == null || stop <= 0) {
        return 'The trail must be smaller than the price';
      }
    }
    const bracketBlock = this.bracketBlocker();
    if (bracketBlock) {
      return bracketBlock;
    }
    if (this.hasStop() && !TradePanelComponent.validPrice(this.stopPrice())) {
      return 'Enter a stop price';
    }
    if (this.hasLimit() && !TradePanelComponent.validPrice(this.limitPrice())) {
      return 'Enter a limit price';
    }
    if (this.hasStop()) {
      const stop = this.stopPrice()!;
      if (this.side() === 'SELL' && stop >= price) {
        return `A sell stop must be below ${this.sideReference()} (${formatMoney(price)})`;
      }
      if (this.side() === 'BUY' && stop <= price) {
        return `A buy stop must be above ${this.sideReference()} (${formatMoney(price)})`;
      }
    }
    if (this.side() === 'BUY' && this.costToHold()! > account.buyingPower) {
      return `Not enough buying power (${formatMoney(account.buyingPower)} available)`;
    }
    if (this.side() === 'SELL' && quantity > sharesHeld) {
      const onHold = this.sharesOnHold();
      if (onHold > 0) {
        return `Only ${sharesHeld} ${sharesHeld === 1 ? 'share is' : 'shares are'} free to sell — ${onHold} ${onHold === 1 ? 'is' : 'are'} held for open orders`;
      }
      return sharesHeld === 0
        ? `You don't own any ${this.symbol()} in this account`
        : `You only have ${sharesHeld} ${sharesHeld === 1 ? 'share' : 'shares'} to sell`;
    }
    return null;
  });

  /** Something worth knowing before placing the order, which doesn't stop it. */
  readonly warning = computed(() => {
    const price = this.sidePrice();
    const limit = this.limitPrice();
    const stop = this.stopPrice();
    const buy = this.side() === 'BUY';
    if (price == null || this.isMarket()) {
      return null;
    }
    if (this.hasTrail()) {
      const distance = distanceTo(this.initialStop(), price);
      return distance && distance.percent < 0.5 ? 'A very tight trail can trigger on ordinary price moves.' : null;
    }
    if (this.orderType() === 'LIMIT' && limit != null) {
      if (buy ? limit >= price : limit <= price) {
        return `Your limit is ${buy ? 'at or above' : 'at or below'} ${this.sideReference()}, so this will fill right away.`;
      }
    }
    if (this.orderType() === 'STOP_LIMIT' && limit != null && stop != null) {
      if (buy ? limit < stop : limit > stop) {
        return `Your limit is ${buy ? 'below' : 'above'} your stop, so this only fills if the price ${buy ? 'falls back' : 'recovers'} after reaching your stop.`;
      }
    }
    const target = this.hasStop() ? stop : limit;
    const distance = distanceTo(target, price);
    if (distance && distance.percent > FAR_FROM_MARKET_PERCENT) {
      return `Your ${this.hasStop() ? 'stop' : 'limit'} is ${Math.round(distance.percent)}% from ${this.sideReference()}, so it may take a long time to reach — or never be reached.`;
    }
    return null;
  });

  /** "0.32% below the ask", for the limit or stop field: measured from the price this side fills at. */
  readonly limitDistance = computed(() => distanceLabel(this.limitPrice(), this.sidePrice(), this.sideReference()));
  readonly stopDistance = computed(() => distanceLabel(this.stopPrice(), this.sidePrice(), this.sideReference()));

  /** One-tap limit prices: around the market, or around the stop for a stop limit. */
  readonly limitChips = computed<PriceChip[]>(() => {
    const buy = this.side() === 'BUY';
    if (this.orderType() === 'STOP_LIMIT') {
      const stop = this.stopPrice();
      if (!TradePanelComponent.validPrice(stop)) {
        return [];
      }
      const steps = [0, 0.5, 1, 2];
      return steps.map(step => ({
        label: step === 0 ? 'At stop' : `${buy ? '+' : '−'}${step}%`,
        value: toCents(stop! * (1 + (buy ? step : -step) / 100))
      }));
    }
    const price = this.sidePrice();
    if (price == null) {
      return [];
    }
    const current = this.quoteOffset() == null ? 'Current' : buy ? 'Ask' : 'Bid';
    return [0, 1, 2, 5].map(step => ({
      // Better than the market: lower for a buy, higher for a sell.
      label: step === 0 ? current : `${buy ? '−' : '+'}${step}%`,
      value: toCents(price * (1 + (buy ? -step : step) / 100))
    }));
  });

  /** One-tap stop prices in the direction the stop protects, plus break-even on a held position. */
  readonly stopChips = computed<PriceChip[]>(() => {
    const price = this.sidePrice();
    if (price == null) {
      return [];
    }
    const buy = this.side() === 'BUY';
    const chips = [2, 5, 10].map(step => ({
      label: `${buy ? '+' : '−'}${step}%`,
      value: toCents(price * (1 + (buy ? step : -step) / 100))
    }));
    const cost = this.averageCost();
    const costBelowPrice = cost != null && cost > 0 && cost < price;
    if (!buy && costBelowPrice && this.sharesOwned() > 0) {
      chips.push({ label: 'Break-even', value: toCents(cost) });
    }
    return chips;
  });

  /** One-tap trails: 1, 2, 5 and 10 percent, or the dollars they come to. */
  readonly trailChips = computed<PriceChip[]>(() => {
    const percents = [1, 2, 5, 10];
    if (this.trailUnit() === 'PERCENT') {
      return percents.map(percent => ({ label: `${percent}%`, value: percent }));
    }
    const price = this.sidePrice();
    return price == null ? [] : percents.map(percent => ({
      label: formatMoney(toCents(price * percent / 100)), value: toCents(price * percent / 100)
    }));
  });

  /**
   * The price lines drawn on the chart. A limit, stop or trailing stop can be dragged to a price;
   * the market line is the price this side fills at.
   */
  readonly chartLines = computed<ChartLine[]>(() => {
    const lines: ChartLine[] = [];
    const market = this.sidePrice();
    if (market != null) {
      lines.push({
        id: 'market', label: this.quoteOffset() ? (this.side() === 'BUY' ? 'Ask' : 'Bid') : 'Price',
        value: market, tone: 'muted', draggable: false
      });
    }
    if (this.hasStop() && TradePanelComponent.validPrice(this.stopPrice())) {
      lines.push({ id: 'stop', label: 'Stop', value: this.stopPrice()!, tone: 'warning', draggable: true });
    }
    if (this.hasTrail() && this.initialStop() != null) {
      lines.push({ id: 'trail', label: 'Stop', value: this.initialStop()!, tone: 'warning', draggable: true });
    }
    if (this.hasLimit() && TradePanelComponent.validPrice(this.limitPrice())) {
      lines.push({ id: 'limit', label: 'Limit', value: this.limitPrice()!, tone: 'accent', draggable: true });
    }
    if (this.bracketActive() && TradePanelComponent.validPrice(this.takeProfit())) {
      lines.push({ id: 'takeProfit', label: 'Take profit', value: this.takeProfit()!, tone: 'gain', draggable: true });
    }
    if (this.bracketActive() && TradePanelComponent.validPrice(this.stopLoss())) {
      lines.push({ id: 'stopLoss', label: 'Stop loss', value: this.stopLoss()!, tone: 'loss', draggable: true });
    }
    return lines;
  });

  /** A line was dragged (or nudged with the keyboard) on the chart to a new price. */
  onChartLine(change: { id: string; value: number }): void {
    const value = toCents(change.value);
    if (!(value > 0)) {
      return;
    }
    if (change.id === 'takeProfit') {
      this.setTakeProfit(value);
    } else if (change.id === 'stopLoss') {
      this.setStopLoss(value);
    } else if (change.id === 'limit') {
      this.setLimitPrice(value);
    } else if (change.id === 'stop') {
      this.setStopPrice(value);
    } else if (change.id === 'trail') {
      // Dragging a trailing stop's line sets how far it trails the price.
      const price = this.sidePrice();
      if (price != null && price > 0) {
        const distance = Math.abs(price - value);
        this.setTrailValue(this.trailUnit() === 'PERCENT' ? Math.round(distance / price * 1000) / 10 : toCents(distance));
      }
    } else {
      // Ignore chart lines that do not represent an editable order price.
    }
  }

  /** The order in plain English, rebuilt as it's edited. */
  readonly sentence = computed(() => describeOrder({
    side: this.side(),
    type: this.orderType(),
    symbol: this.symbol() ?? '',
    quantity: this.validQuantity() ? this.quantity() : null,
    limitPrice: this.limitPrice(),
    stopPrice: this.stopPrice(),
    timeInForce: this.timeInForce(),
    trailAmount: this.trailUnit() === 'AMOUNT' ? this.trailValue() : null,
    trailPercent: this.trailUnit() === 'PERCENT' ? this.trailValue() : null,
    bracketTakeProfit: this.bracketActive() ? this.takeProfit() : null,
    bracketStopLoss: this.bracketActive() ? this.stopLoss() : null
  }));

  /** "5%" or "$2.00": the trail as entered. */
  readonly trailLabel = computed(() => trailText(
    this.trailUnit() === 'AMOUNT' ? this.trailValue() : null,
    this.trailUnit() === 'PERCENT' ? this.trailValue() : null));

  readonly sentenceLabel = computed(() => sentenceText(this.sentence()));

  readonly canReview = computed(() => this.validQuantity() && this.blocker() === null);

  sellAll(): void {
    this.quantity.set(this.sharesHeld() || null);
  }

  adjust(delta: number): void {
    this.quantity.set(Math.max(1, (this.validQuantity() ? this.quantity()! : 0) + delta));
  }

  review(): void {
    if (this.canReview()) {
      // Prices are sent in cents; show the review with what will be sent.
      if (this.limitPrice() != null) { this.limitPrice.set(toCents(this.limitPrice()!)); }
      if (this.stopPrice() != null) { this.stopPrice.set(toCents(this.stopPrice()!)); }
      if (this.takeProfit() != null) { this.takeProfit.set(toCents(this.takeProfit()!)); }
      if (this.stopLoss() != null) { this.stopLoss.set(toCents(this.stopLoss()!)); }
      this.step.set('review');
      this.quoteForReview();
    }
  }

  /**
   * Captures the price the order is reviewed at. The streamed price stands in at once, so Submit
   * never waits; the quote's ask (buy) or bid (sell) - what the order actually fills at - replaces
   * it when it arrives. Only market orders are measured against it.
   */
  private quoteForReview(): void {
    const symbol = this.symbol();
    const side = this.side();
    this.quotedPrice.set(this.sidePrice());
    this.quoteRequest?.unsubscribe();
    if (!symbol || !this.isMarket()) {
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

  /** The order as order-app takes it. A market order is sent exactly as before order types. */
  private orderRequest(accountId: string, symbol: string): OrderSubmissionRequest {
    const bracket = this.bracketActive()
      ? { bracketTakeProfitPrice: toCents(this.takeProfit()!), bracketStopLossPrice: toCents(this.stopLoss()!) }
      : {};
    const base = { accountId, symbol, side: this.side(), quantity: this.quantity()!, ...bracket };
    if (!this.isMarket()) {
      return {
        ...base,
        orderType: this.orderType(),
        ...(this.hasLimit() ? { limitPrice: toCents(this.limitPrice()!) } : {}),
        ...(this.hasStop() ? { stopPrice: toCents(this.stopPrice()!) } : {}),
        ...(this.hasTrail()
          ? this.trailUnit() === 'PERCENT' ? { trailPercent: this.trailValue()! } : { trailAmount: toCents(this.trailValue()!) }
          : {}),
        timeInForce: this.timeInForce()
      };
    }
    const quotedPrice = this.quotedPrice();
    const protection = this.protection();
    return {
      ...base,
      ...(quotedPrice != null ? { quotedPrice } : {}),
      ...(protection != null ? { maxSlippagePercent: protection } : {})
    };
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
    this.orders.submitOrder(this.orderRequest(accountId, symbol)).subscribe({
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
    // The order type sticks between tickets; its prices are suggested afresh for the new one.
    this.pricesTouched.set(false);
    this.bracketOn.set(false);
    this.bracketTouched.set(false);
    this.takeProfit.set(null);
    this.stopLoss.set(null);
    this.limitPrice.set(null);
    this.stopPrice.set(null);
    this.trailValue.set(this.orderType() === 'TRAILING_STOP' ? this.defaultTrail() : null);
  }

  readonly fillPrice = computed(() => this.result()?.execution?.fillPrice ?? null);

  /** For an OPEN result: what it's waiting for, e.g. "AAPL falls to $140.00". */
  readonly waitingFor = computed(() => {
    const result = this.result();
    if (!result || result.status !== 'OPEN') {
      return null;
    }
    const buy = result.side === 'BUY';
    if (result.orderType === 'TRAILING_STOP') {
      return `${result.symbol} ${buy ? 'rises' : 'falls'} ${trailText(result.trailAmount, result.trailPercent)} from its ${buy ? 'low' : 'high'}`;
    }
    if (result.orderType === 'LIMIT') {
      return `${result.symbol} ${buy ? 'falls' : 'rises'} to ${formatMoney(result.limitPrice)}`;
    }
    return `${result.symbol} ${buy ? 'rises' : 'falls'} to ${formatMoney(result.stopPrice)}`;
  });
}
