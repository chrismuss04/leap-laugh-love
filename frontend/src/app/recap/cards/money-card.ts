import { Component, computed, input } from '@angular/core';
import { RecapMoneyMoved } from '../../services/recap';
import { formatMoney } from '../../shared/format';
import { formatWholeMoney, plural } from '../recap-format';
import { CountUpDirective } from '../count-up';

/** Card 2: how much the client traded, buys and sells together. */
@Component({
    selector: 'app-recap-money-card',
    imports: [CountUpDirective],
    template: `
    <div class="abs copy">
      <h2 class="display" data-a style="--i:0">My Money Moved</h2>
      <div class="display big" data-a="pop" style="--i:1"
           [appCountUp]="moneyMoved().total" [countUpFormat]="wholeMoney" [countUpDelay]="190"></div>
      <p class="sub" data-a style="--i:3">That’s <b>{{ perDay() }}</b> per market day</p>
      <p class="meta" data-a style="--i:4">{{ orders() }} across {{ accounts() }}</p>
    </div>`,
    styleUrls: ['./card.css', './money-card.css']
})
export class RecapMoneyCardComponent {
  readonly moneyMoved = input.required<RecapMoneyMoved>();

  readonly wholeMoney = formatWholeMoney;
  readonly perDay = computed(() => formatMoney(this.moneyMoved().perMarketDay));
  readonly orders = computed(() => plural(this.moneyMoved().filledOrders, 'filled order'));
  readonly accounts = computed(() => plural(this.moneyMoved().accounts, 'account'));
}
