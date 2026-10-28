import { Component, computed, input } from '@angular/core';
import { RecapTopGainer } from '../../services/recap';
import { linePath, monthName } from '../recap-format';
import { CountUpDirective } from '../count-up';
import { RecapFootComponent } from './recap-foot';

/**
 * Card 4: the symbol with the best return, its month drawn on the falling disc. The ranking under
 * it is the client's overall trading return against everyone else's, not this symbol's, so the
 * line says so.
 */
@Component({
    selector: 'app-recap-gainer-card',
    imports: [CountUpDirective, RecapFootComponent],
    template: `
    <svg class="rings" viewBox="180 -145 720 1120" aria-hidden="true">
      <defs><clipPath id="recap-disc"><circle cx="540" cy="615" r="355" /></clipPath></defs>
      <g fill="var(--recap-ink)" stroke="var(--recap-dim)" stroke-width="4">
        <circle data-a="drop" style="--i:0" cx="540" cy="215" r="355" />
        <circle data-a="drop" style="--i:1" cx="540" cy="315" r="355" />
        <circle data-a="drop" style="--i:2" cx="540" cy="415" r="355" />
        <circle data-a="drop" style="--i:3" cx="540" cy="515" r="355" />
      </g>
      <g data-a="drop" style="--i:4">
        <circle cx="540" cy="615" r="355" fill="var(--recap-panel)" stroke="var(--recap-dim)" stroke-width="4" />
        @if (line()) {
          <g clip-path="url(#recap-disc)">
            <path [attr.d]="line() + 'L895,980L185,980Z'" fill="var(--recap-lime)" opacity=".18" class="area" />
            <path [attr.d]="line()" fill="none" stroke="var(--recap-lime)" stroke-width="10" stroke-linejoin="round"
                  stroke-linecap="round" pathLength="1" class="spark" />
          </g>
        }
      </g>
    </svg>
    <div class="abs box" data-a style="--i:5">
      <div class="display box-title">My Top Gainer</div>
      <div class="figure box-name">{{ gainer()?.symbol ?? '—' }}</div>
    </div>
    <div class="abs result">
      @if (gainer(); as top) {
        <div class="up" data-a style="--i:7">Up</div>
        <div class="figure pct text-gain" data-a="pop" style="--i:8"
             [appCountUp]="top.returnPct" [countUpFormat]="twoPlaces" [countUpDelay]="680"></div>
      } @else {
        <p class="empty-note" data-a style="--i:7">{{ emptyNote() }}</p>
      }
      @if (topPercent() !== null) {
        <div class="rank" data-a style="--i:9">My overall return: top <b>{{ topPercent() }}%</b> of Leapfolio traders</div>
      }
    </div>
    <app-recap-foot class="foot" data-a style="--i:10" />`,
    styleUrls: ['./card.css', './gainer-card.css']
})
export class RecapGainerCardComponent {
  /** yyyy-MM */
  readonly month = input.required<string>();
  /** Null when none of the client's positions gained. */
  readonly gainer = input.required<RecapTopGainer | null>();
  readonly topPercent = input<number | null>(null);
  /** Whether the client had any position a return could be worked out for. */
  readonly hasReturn = input(true);

  readonly twoPlaces = (value: number) => value.toFixed(2) + '%';
  readonly line = computed(() => linePath(this.gainer()?.closes ?? [], 185, 470, 710, 300));
  readonly emptyNote = computed(() => this.hasReturn()
    ? `None of your positions gained in ${monthName(this.month())}.`
    : `Nothing to rank in ${monthName(this.month())}.`);
}
