import { Component, computed, input } from '@angular/core';
import { RecapPayload, RecapSymbolReturn } from '../../services/recap';
import { formatSignedPercentTo, formatSignedWholeMoney, linePath, monthName, monthYear } from '../recap-format';
import { CountUpDirective } from '../count-up';
import { RecapFootComponent } from './recap-foot';

// Horizontal guide lines across the P&L chart, in its 787-unit viewBox.
const GRID_LINES = [300, 405, 510, 615, 720];

/** Card 6: the month's P&L chart, the best and worst symbols, and two closing stats. */
@Component({
    selector: 'app-recap-tops-card',
    imports: [CountUpDirective, RecapFootComponent],
    template: `
    <div class="abs display year" data-a="drop" style="--i:0" [attr.data-year]="year()" aria-hidden="true">{{ year() }}</div>
    <div class="abs square" data-a="pop" style="--i:1">
      <svg viewBox="0 0 787 787" preserveAspectRatio="none" aria-hidden="true">
        @for (y of gridLines; track y; let g = $index) {
          <line x1="40" x2="747" [attr.y1]="y" [attr.y2]="y" stroke="#262626" stroke-opacity=".08" stroke-width="2"
                class="grid" [style.--g]="g" />
        }
        @if (line()) {
          <path [attr.d]="line() + 'L747,760L40,760Z'" fill="var(--recap-lime)" opacity=".4" class="area" />
          <path [attr.d]="line()" fill="none" stroke="#262626" stroke-width="7" stroke-linejoin="round" pathLength="1" class="spark" />
        }
      </svg>
      <div class="sq-head">
        <div class="sq-label">Trading P&amp;L, {{ name() }}</div>
        <div class="sq-value display"
             [appCountUp]="recap().tradingReturn.pnl" [countUpFormat]="signedWholeMoney" [countUpDelay]="190"></div>
      </div>
    </div>
    <div class="abs lists">
      <div class="col" data-a style="--i:2">
        <div class="lbl">Top Gainers</div>
        <ol>
          @for (row of gainers(); track row.symbol; let k = $index) {
            <li [style.--r]="k + 2"><span class="n">{{ k + 1 }}</span>{{ row.symbol }}<em>{{ row.pct }}</em></li>
          } @empty {
            <li class="none">None this month</li>
          }
        </ol>
      </div>
      <div class="col" data-a style="--i:3">
        <div class="lbl">Biggest Losers</div>
        <ol>
          @for (row of losers(); track row.symbol; let k = $index) {
            <li [style.--r]="k + 3"><span class="n">{{ k + 1 }}</span>{{ row.symbol }}<em>{{ row.pct }}</em></li>
          } @empty {
            <li class="none">None this month</li>
          }
        </ol>
      </div>
      <div class="stat" data-a style="--i:4"><div class="lbl">Instruments Traded</div><div class="v display">{{ recap().instrumentsTraded }}</div></div>
      <div class="stat" data-a style="--i:5"><div class="lbl">Top Sector</div><div class="v display">{{ recap().topSector?.label ?? '—' }}</div></div>
    </div>
    <app-recap-foot class="foot" data-a style="--i:6" />`,
    styleUrls: ['./card.css', './tops-card.css']
})
export class RecapTopsCardComponent {
  readonly recap = input.required<RecapPayload>();

  readonly gridLines = GRID_LINES;
  readonly year = computed(() => monthYear(this.recap().month));
  readonly name = computed(() => monthName(this.recap().month));
  readonly signedWholeMoney = formatSignedWholeMoney;
  readonly line = computed(() => linePath(this.recap().tradingReturn.dailyPnl, 40, 300, 707, 420));
  readonly gainers = computed(() => rows(this.recap().topGainers));
  readonly losers = computed(() => rows(this.recap().biggestLosers));
}

function rows(returns: RecapSymbolReturn[]): { symbol: string; pct: string }[] {
  return returns.map(r => ({ symbol: r.symbol, pct: formatSignedPercentTo(r.returnPct, 1) }));
}
