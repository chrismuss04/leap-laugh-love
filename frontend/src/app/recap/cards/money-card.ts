import { Component, computed, input } from '@angular/core';
import { RecapMoneyMoved } from '../../services/recap';
import { formatWholeMoney, plural } from '../recap-format';
import { CountUpDirective } from '../count-up';

/** Card 2: the client's trading volume, buys and sells together, then split. */
@Component({
    selector: 'app-recap-money-card',
    imports: [CountUpDirective],
    template: `
    <div class="abs copy">
      <h2 class="display" data-a style="--i:0">My Trading Volume</h2>
      <div class="display big" data-a="pop" style="--i:1"
           [appCountUp]="moneyMoved().total" [countUpFormat]="wholeMoney" [countUpDelay]="190"></div>
      @if (moneyMoved().filledOrders > 0) {
        <p class="sub" data-a style="--i:3"><b>{{ bought() }}</b> bought · <b>{{ sold() }}</b> sold</p>
        <p class="meta" data-a style="--i:4">{{ orders() }} across {{ accounts() }}</p>
      } @else {
        <p class="sub" data-a style="--i:3">No trades this month</p>
      }
    </div>`,
    styleUrls: ['./card.css', './money-card.css']
})
export class RecapMoneyCardComponent {
  readonly moneyMoved = input.required<RecapMoneyMoved>();

  readonly wholeMoney = formatWholeMoney;
  readonly bought = computed(() => formatWholeMoney(this.moneyMoved().bought));
  readonly sold = computed(() => formatWholeMoney(this.moneyMoved().sold));
  readonly orders = computed(() => plural(this.moneyMoved().filledOrders, 'filled order'));
  readonly accounts = computed(() => plural(this.moneyMoved().accounts, 'account'));
}
