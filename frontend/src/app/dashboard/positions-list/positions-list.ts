import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { HoldingView } from '../models';
import { OpenOrder } from '../../services/order';
import { openOrderTitle, waitingPrice } from '../../shared/order-types';
import { SparklineComponent } from '../sparkline/sparkline';
import { MarketSession } from '../../shared/market-session';
import { RollingNumberComponent } from '../../shared/rolling-number';
import { direction, formatMoney, formatSignedMoney, formatSignedPercent } from '../../shared/format';

/** The client's holdings. Selecting a row loads that symbol into the trade panel. */
@Component({
    selector: 'app-positions-list',
    imports: [CommonModule, RouterLink, SparklineComponent, RollingNumberComponent],
    templateUrl: './positions-list.html',
    styleUrl: './positions-list.css'
})
export class PositionsListComponent {
  @Input() holdings: HoldingView[] = [];
  @Input() loading = false;
  @Input() selected: string | null = null;
  /** Open orders in scope, shown as a pill on their symbol's row. */
  @Input() openOrders: OpenOrder[] = [];
  /** The session under way, which the sparklines draw at full strength. */
  @Input() session: MarketSession | null = null;
  @Output() select = new EventEmitter<string>();
  @Output() trade = new EventEmitter<{ symbol: string; side: 'BUY' | 'SELL' }>();

  money = formatMoney;
  signedMoney = formatSignedMoney;
  signedPercent = formatSignedPercent;
  dir = direction;

  /** A row's open-order pill: the price the first order waits for, or how many there are. */
  orderPill(symbol: string): { text: string; label: string } | null {
    const orders = this.openOrders.filter(o => o.symbol === symbol);
    if (!orders.length) {
      return null;
    }
    const label = orders
      .map(o => `${openOrderTitle(o)} of ${o.quantity} at ${formatMoney(waitingPrice(o))}`)
      .join('; ');
    return {
      text: orders.length === 1 ? formatMoney(waitingPrice(orders[0])) : `${orders.length} open`,
      label: `Open orders: ${label}`
    };
  }

  trackBySymbol(_: number, holding: HoldingView): string {
    return holding.symbol;
  }
}
