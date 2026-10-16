import { Component, computed, input } from '@angular/core';
import { RecapPayload, RecapSymbolReturn } from '../../services/recap';
import { RecapFooterComponent } from './recap-footer';
import { signedPercent1, signedWholeMoney } from './recap-format';
import { areaPath, linePath } from './sparkline';

/** Card 6: the month in lists and one chart, after "The Tops", on a plain card under the year. */
@Component({
  selector: 'app-the-tops-card',
  imports: [RecapFooterComponent],
  template: `
    <div class="abs display year" data-a="drop" style="--i:0" aria-hidden="true" [attr.data-year]="year()">{{ year() }}</div>
    <div class="abs square" data-a="pop" style="--i:1">
      <svg viewBox="0 0 787 787" preserveAspectRatio="none" aria-hidden="true">
        @for (y of gridLines; track y) {
          <line x1="40" x2="747" [attr.y1]="y" [attr.y2]="y" stroke="#262626" stroke-opacity=".08" stroke-width="2" />
        }
        @if (line()) {
          <path [attr.d]="area()" fill="#2dd4bf" opacity=".4" />
          <path [attr.d]="line()" fill="none" stroke="#262626" stroke-width="7" stroke-linejoin="round" pathLength="1" class="spark" />
        }
      </svg>
      <div class="sq-head">
        <div class="sq-label">Trading P&amp;L, {{ monthName() }}</div>
        <div class="sq-value display">{{ pnl() }}</div>
      </div>
    </div>
    <div class="abs lists">
      <div class="col" data-a style="--i:2">
        <div class="lbl">Top Gainers</div>
        <ol>
          @for (row of gainers(); track row.symbol; let k = $index) {
            <li><span class="n">{{ k + 1 }}</span>{{ row.symbol }}<em>{{ row.pct }}</em></li>
          } @empty {
            <li class="none">—</li>
          }
        </ol>
      </div>
      <div class="col" data-a style="--i:3">
        <div class="lbl">Biggest Losers</div>
        <ol>
          @for (row of losers(); track row.symbol; let k = $index) {
            <li><span class="n">{{ k + 1 }}</span>{{ row.symbol }}<em>{{ row.pct }}</em></li>
          } @empty {
            <li class="none">—</li>
          }
        </ol>
      </div>
      <div class="stat" data-a style="--i:4">
        <div class="lbl">Instruments Traded</div>
        <div class="v display">{{ recap().instrumentsTraded }}</div>
      </div>
      <div class="stat" data-a style="--i:5">
        <div class="lbl">Top Sector</div>
        <div class="v display">{{ recap().topSector?.label ?? '—' }}</div>
      </div>
    </div>
    <app-recap-footer data-a style="--i:6" />`,
  styleUrl: './the-tops-card.css'
})
export class TheTopsCardComponent {
  readonly recap = input.required<RecapPayload>();
  readonly monthName = input.required<string>();

  readonly gridLines = [300, 405, 510, 615, 720];
  readonly year = computed(() => this.recap().month.slice(0, 4));
  readonly pnl = computed(() => signedWholeMoney(this.recap().tradingReturn.pnl));
  // A month with no P&L at all gets an empty chart rather than a flat line that looks like a value.
  readonly line = computed(() => {
    const daily = this.recap().tradingReturn.dailyPnl;
    return daily.some(pnl => pnl !== 0) ? linePath(daily, 40, 300, 707, 420) : '';
  });
  readonly area = computed(() => areaPath(this.line(), 40, 707, 760));
  readonly gainers = computed(() => this.rows(this.recap().topGainers));
  readonly losers = computed(() => this.rows(this.recap().biggestLosers));

  private rows(returns: RecapSymbolReturn[]): { symbol: string; pct: string }[] {
    return returns.map(r => ({ symbol: r.symbol, pct: signedPercent1(r.returnPct) }));
  }
}
