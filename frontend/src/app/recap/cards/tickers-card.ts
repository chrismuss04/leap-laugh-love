import { Component, computed, input } from '@angular/core';
import { RecapTicker } from '../../services/recap';
import { formatWholeMoney, linePath } from '../recap-format';
import { RecapFootComponent } from './recap-foot';

/** Card 3: the symbols the client traded most, by value, each with its month as a sparkline. */
@Component({
    selector: 'app-recap-tickers-card',
    imports: [RecapFootComponent],
    template: `
    <div class="abs chip display title" data-a style="--i:0">My Top Tickers</div>
    @for (row of rows(); track row.symbol; let k = $index, last = $last) {
      <div class="tk" data-a [style.--k]="k" [style.--i]="k + 1">
        <div class="rk display">{{ k + 1 }}</div>
        <div class="tile" [class.last]="last">
          <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
            @if (row.path) {
              <path [attr.d]="row.path" fill="none" [attr.stroke]="k === 0 ? 'var(--recap-lime)' : 'var(--recap-ink)'"
                    stroke-width="4" vector-effect="non-scaling-stroke" pathLength="1" class="spark" />
            }
          </svg>
        </div>
        <div class="tx"><span class="chip display">{{ row.symbol }}</span><small>{{ row.traded }} traded</small></div>
      </div>
    }
    <app-recap-foot class="foot" data-a [style.--i]="rows().length + 2" />`,
    styleUrls: ['./card.css', './tickers-card.css']
})
export class RecapTickersCardComponent {
  readonly tickers = input.required<RecapTicker[]>();

  readonly rows = computed(() => this.tickers().map(ticker => ({
    symbol: ticker.symbol,
    traded: formatWholeMoney(ticker.tradedValue),
    path: linePath(ticker.closes, 10, 22, 80, 56)
  })));
}
