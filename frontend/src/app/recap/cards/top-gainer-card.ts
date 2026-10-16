import { Component, computed, input } from '@angular/core';
import { RecapPayload } from '../../services/recap';
import { RecapFooterComponent } from './recap-footer';
import { percent2 } from './recap-format';
import { areaPath, linePath } from './sparkline';

/**
 * Card 4: the symbol with the best return, its month drawn inside the front disc, after "My Top
 * Artist". The ranking line is about the client's whole trading return, not the symbol, and says so.
 */
@Component({
  selector: 'app-top-gainer-card',
  imports: [RecapFooterComponent],
  template: `
    <svg class="rings" viewBox="180 -145 720 1120" aria-hidden="true">
      <defs><clipPath id="recap-gainer-disc"><circle cx="540" cy="615" r="355" /></clipPath></defs>
      <g fill="#262626" stroke="#eeefea" stroke-width="6">
        <circle data-a="drop" style="--i:0" cx="540" cy="215" r="355" />
        <circle data-a="drop" style="--i:1" cx="540" cy="315" r="355" />
        <circle data-a="drop" style="--i:2" cx="540" cy="415" r="355" />
        <circle data-a="drop" style="--i:3" cx="540" cy="515" r="355" />
      </g>
      <g data-a="drop" style="--i:4">
        <circle cx="540" cy="615" r="355" fill="#eeefea" stroke="#eeefea" stroke-width="6" />
        @if (line()) {
          <g clip-path="url(#recap-gainer-disc)">
            <path [attr.d]="area()" fill="#2dd4bf" opacity=".35" />
            <path [attr.d]="line()" fill="none" stroke="#262626" stroke-width="10" stroke-linejoin="round"
                  stroke-linecap="round" pathLength="1" class="spark" />
          </g>
        }
      </g>
    </svg>
    <div class="abs box" data-a style="--i:5">
      <div class="display box-title">My Top Gainer</div>
      <div class="box-name">{{ recap().topGainer?.symbol ?? '—' }}</div>
    </div>
    <div class="abs result">
      @if (recap().topGainer) {
        <div class="up" data-a style="--i:7">Up</div>
        <div class="display pct" data-a="pop" style="--i:8">{{ pct() }}</div>
      } @else {
        <p class="empty-note" data-a style="--i:7">{{ emptyNote() }}</p>
      }
      @if (recap().topPercent !== null) {
        <div class="rank" data-a style="--i:9"><span class="lead">Your trading return: </span>top <b>{{ recap().topPercent }}%</b> of Leapfolio traders</div>
      }
    </div>
    <app-recap-footer data-a style="--i:10" />`,
  styleUrl: './top-gainer-card.css'
})
export class TopGainerCardComponent {
  readonly recap = input.required<RecapPayload>();
  readonly monthName = input.required<string>();

  readonly line = computed(() => linePath(this.recap().topGainer?.closes ?? [], 185, 470, 710, 300));
  readonly area = computed(() => areaPath(this.line(), 185, 710, 980));
  readonly pct = computed(() => percent2(this.recap().topGainer?.returnPct ?? 0));
  readonly emptyNote = computed(() => this.recap().tradingReturn.returnPct === null
    ? `Nothing to rank in ${this.monthName()}.`
    : `None of your positions gained in ${this.monthName()}.`);
}
