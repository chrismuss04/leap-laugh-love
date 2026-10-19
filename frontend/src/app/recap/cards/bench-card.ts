import { Component, computed, input } from '@angular/core';
import { RecapBenchmark } from '../../services/recap';
import { benchBars, formatPoints, formatSignedPercentTo, monthName } from '../recap-format';
import { CountUpDirective } from '../count-up';

/** Card 5: the client's trading return beside the S&P 500's move over the same month. */
@Component({
    selector: 'app-recap-bench-card',
    imports: [CountUpDirective],
    template: `
    <div class="abs copy">
      <h2 class="display" data-a style="--i:0">Me vs the {{ indexName() }}</h2>
      <div class="bench me" data-a style="--i:1">
        <span class="lbl">My trading return</span>
        @if (returnPct() !== null) {
          <span class="display val" [appCountUp]="returnPct()!" [countUpFormat]="onePlace" [countUpDelay]="190"></span>
        } @else {
          <span class="display val">—</span>
        }
        <span class="bar"><i [style.width.%]="bars().mine" style="--i:1"></i></span>
      </div>
      @if (benchmark(); as index) {
        <div class="bench index" data-a style="--i:2">
          <span class="lbl">{{ index.name }}</span>
          <span class="display val" [appCountUp]="index.returnPct" [countUpFormat]="onePlace" [countUpDelay]="260"></span>
          <span class="bar"><i [style.width.%]="bars().index" style="--i:2"></i></span>
        </div>
      }
      <p class="verdict" data-a style="--i:4">
        @switch (outcome()) {
          @case ('beat') { You beat the market by <b>{{ points() }}</b> in {{ name() }}. }
          @case ('trailed') { The market beat you by <b>{{ points() }}</b> in {{ name() }}. }
          @case ('no-return') { No trading return to compare in {{ name() }}. }
          @case ('no-index') { The {{ indexName() }}’s move isn’t available for {{ name() }}. }
          @default { You kept pace with the market in {{ name() }}. }
        }
      </p>
    </div>`,
    styleUrls: ['./card.css', './bench-card.css']
})
export class RecapBenchCardComponent {
  /** yyyy-MM */
  readonly month = input.required<string>();
  /** Null when no position could be priced. */
  readonly returnPct = input.required<number | null>();
  /** Null when the index has no prices for the month. */
  readonly benchmark = input.required<RecapBenchmark | null>();

  readonly name = computed(() => monthName(this.month()));
  readonly indexName = computed(() => this.benchmark()?.name ?? 'S&P 500');
  readonly onePlace = (value: number) => formatSignedPercentTo(value, 1);
  readonly bars = computed(() => benchBars(this.returnPct() ?? 0, this.benchmark()?.returnPct ?? 0));
  /** Null when there's nothing to compare. */
  readonly difference = computed(() => {
    const mine = this.returnPct();
    const index = this.benchmark();
    return mine === null || index === null ? null : index.differencePts ?? mine - index.returnPct;
  });
  readonly points = computed(() => formatPoints(this.difference() ?? 0));
  // Judged at the shown precision, so "by 0.0 points" never appears.
  readonly outcome = computed(() => {
    if (this.returnPct() === null) {
      return 'no-return';
    }
    const difference = this.difference();
    if (difference === null) {
      return 'no-index';
    }
    const shown = Number(difference.toFixed(1));
    if (shown > 0) {
      return 'beat';
    }
    return shown < 0 ? 'trailed' : 'level';
  });
}
