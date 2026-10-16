import { Component, computed, input } from '@angular/core';
import { RecapPayload } from '../../services/recap';
import { points1, signedPercent1 } from './recap-format';

/**
 * Card 5: the client's trading return against the S&P 500's move over the month, as two bars
 * scaled to the larger of the two.
 */
@Component({
  selector: 'app-vs-sp500-card',
  template: `
    <div class="abs copy">
      <h2 class="display" data-a style="--i:0">Me vs the S&amp;P&nbsp;500</h2>
      <div class="bench me" data-a style="--i:1">
        <span class="lbl">My trading return</span>
        <span class="display val" [class.outlined]="recap().tradingReturn.returnPct !== null">{{ mine() }}</span>
        <span class="bar"><i [style.width.%]="myBar()" style="--i:1"></i></span>
      </div>
      @if (recap().benchmark; as index) {
        <div class="bench spx" data-a style="--i:2">
          <span class="lbl">{{ index.name }}</span>
          <span class="display val">{{ market() }}</span>
          <span class="bar"><i [style.width.%]="marketBar()" style="--i:2"></i></span>
        </div>
      }
      <p class="verdict" data-a style="--i:4">
        @switch (verdict().kind) {
          @case ('beat') { You beat the market by <b>{{ verdict().points }} points</b> in {{ monthName() }}. }
          @case ('behind') { The market beat you by <b>{{ verdict().points }} points</b> in {{ monthName() }}. }
          @case ('even') { You matched the market in {{ monthName() }}. }
          @case ('no-return') { No trading return to compare in {{ monthName() }}. }
          @default { The S&amp;P&nbsp;500's move isn't available for {{ monthName() }}. }
        }
      </p>
    </div>`,
  styleUrl: './vs-sp500-card.css'
})
export class VsSp500CardComponent {
  readonly recap = input.required<RecapPayload>();
  readonly monthName = input.required<string>();

  readonly mine = computed(() => signedPercent1(this.recap().tradingReturn.returnPct));
  readonly market = computed(() => signedPercent1(this.recap().benchmark?.returnPct));

  /** Each bar's length as a share of the longer one: the bars compare sizes, the figures give signs. */
  private readonly scale = computed(() => Math.max(
    Math.abs(this.recap().tradingReturn.returnPct ?? 0), Math.abs(this.recap().benchmark?.returnPct ?? 0)));
  readonly myBar = computed(() => this.share(this.recap().tradingReturn.returnPct));
  readonly marketBar = computed(() => this.share(this.recap().benchmark?.returnPct));

  readonly verdict = computed((): { kind: 'beat' | 'behind' | 'even' | 'no-return' | 'no-index'; points: string } => {
    const { tradingReturn, benchmark } = this.recap();
    if (tradingReturn.returnPct === null) {
      return { kind: 'no-return', points: '' };
    }
    if (!benchmark || benchmark.differencePts === null) {
      return { kind: 'no-index', points: '' };
    }
    const points = points1(benchmark.differencePts);
    if (points === '0.0') {
      return { kind: 'even', points };
    }
    return { kind: benchmark.differencePts > 0 ? 'beat' : 'behind', points };
  });

  private share(value: number | null | undefined): number {
    return value == null || this.scale() === 0 ? 0 : (Math.abs(value) / this.scale()) * 100;
  }
}
