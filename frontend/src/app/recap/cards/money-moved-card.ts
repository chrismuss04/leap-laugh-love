import { Component, computed, input } from '@angular/core';
import { RecapPayload } from '../../services/recap';
import { formatMoney } from '../../shared/format';
import { count, wholeMoney } from './recap-format';

/** Card 2: how much the client traded, after the Figma "minutes listened" card. */
@Component({
  selector: 'app-money-moved-card',
  template: `
    <div class="abs copy">
      <h2 class="display" data-a style="--i:0">My Money Moved</h2>
      <div class="display big outlined" data-a="pop" style="--i:1">{{ total() }}</div>
      @if (recap().moneyMoved.filledOrders > 0) {
        <p class="sub" data-a style="--i:3">That's <b>{{ perDay() }}</b> per market day</p>
        <p class="meta" data-a style="--i:4">{{ orders() }} across {{ accounts() }}</p>
      } @else {
        <p class="sub" data-a style="--i:3">No trades this month</p>
      }
    </div>`,
  styleUrl: './money-moved-card.css'
})
export class MoneyMovedCardComponent {
  readonly recap = input.required<RecapPayload>();
  readonly monthName = input.required<string>();

  readonly total = computed(() => wholeMoney(this.recap().moneyMoved.total));
  readonly perDay = computed(() => formatMoney(this.recap().moneyMoved.perMarketDay));
  readonly orders = computed(() => count(this.recap().moneyMoved.filledOrders, 'filled order'));
  readonly accounts = computed(() => count(this.recap().moneyMoved.accounts, 'account'));
}
