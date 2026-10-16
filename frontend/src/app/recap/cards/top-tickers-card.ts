import { Component, computed, input } from '@angular/core';
import { RecapPayload } from '../../services/recap';
import { RecapFooterComponent } from './recap-footer';
import { wholeMoney } from './recap-format';
import { linePath } from './sparkline';

/** Card 3: the five most traded symbols, each with its month on a sparkline, after "My Top Artists". */
@Component({
  selector: 'app-top-tickers-card',
  imports: [RecapFooterComponent],
  template: `
    <div class="abs chip title" data-a style="--i:0">My Top Tickers</div>
    @for (row of rows(); track row.symbol; let k = $index) {
      <div class="tk" data-a [style.--k]="k" [style.--i]="k + 1">
        <div class="rk display">{{ k + 1 }}</div>
        <div class="tile">
          @if (row.path) {
            <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
              <path [attr.d]="row.path" fill="none" [attr.stroke]="k === 0 ? 'var(--accent)' : 'var(--ink)'"
                    stroke-width="4" vector-effect="non-scaling-stroke" pathLength="1" class="spark" />
            </svg>
          }
        </div>
        <div class="tx">
          <span class="chip">{{ row.symbol }}</span>
          <small>{{ row.traded }} traded</small>
        </div>
      </div>
    } @empty {
      <p class="abs empty-note none">No trades this month, so no top tickers.</p>
    }
    <app-recap-footer data-a style="--i:7" />`,
  styleUrl: './top-tickers-card.css'
})
export class TopTickersCardComponent {
  readonly recap = input.required<RecapPayload>();
  readonly monthName = input.required<string>();

  readonly rows = computed(() => this.recap().topTickers.map(ticker => ({
    symbol: ticker.symbol,
    traded: wholeMoney(ticker.tradedValue),
    path: linePath(ticker.closes, 10, 22, 80, 56)
  })));
}
