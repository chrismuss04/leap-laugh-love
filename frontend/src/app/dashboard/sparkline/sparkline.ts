import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';

/** A tiny trend line with a dotted baseline, coloured by direction against that baseline. */
@Component({
    selector: 'app-sparkline',
    imports: [CommonModule],
    template: `
    <svg *ngIf="path; else empty" [attr.viewBox]="'0 0 ' + width + ' ' + height"
         [attr.width]="width" [attr.height]="height" aria-hidden="true" [attr.data-tone]="tone">
      <line *ngIf="baselineY !== null" x1="0" [attr.x2]="width" [attr.y1]="baselineY" [attr.y2]="baselineY" class="base" />
      <path [attr.d]="path" class="line" />
    </svg>
    <ng-template #empty><span class="empty" [style.width.px]="width" [style.height.px]="height"></span></ng-template>
  `,
    styles: [`
    :host { display: inline-flex; }
    .line { fill: none; stroke: var(--gain); stroke-width: 1.5; stroke-linejoin: round; vector-effect: non-scaling-stroke; }
    svg[data-tone='loss'] .line { stroke: var(--loss); }
    svg[data-tone='flat'] .line { stroke: var(--muted-foreground); }
    .base { stroke: var(--muted-foreground); stroke-dasharray: 1 3; stroke-width: 1; opacity: 0.5; vector-effect: non-scaling-stroke; }
    .empty { display: inline-block; }
  `]
})
export class SparklineComponent implements OnChanges {
  @Input() values: number[] = [];
  /** The value the trend is judged against, e.g. the previous close. Defaults to the first value. */
  @Input() baseline: number | null = null;
  @Input() width = 88;
  @Input() height = 32;

  path = '';
  baselineY: number | null = null;
  tone: 'gain' | 'loss' | 'flat' = 'flat';

  ngOnChanges(): void {
    const values = this.values;
    if (values.length < 2) {
      this.path = '';
      return;
    }
    const base = this.baseline ?? values[0];
    const min = Math.min(base, ...values);
    const max = Math.max(base, ...values);
    const span = max - min || 1;
    const pad = 2;
    const y = (v: number) => pad + ((max - v) / span) * (this.height - pad * 2);
    this.path = values
      .map((v, i) => `${i === 0 ? 'M' : 'L'}${((i / (values.length - 1)) * this.width).toFixed(1)},${y(v).toFixed(1)}`)
      .join('');
    this.baselineY = y(base);
    const last = values[values.length - 1];
    this.tone = last > base ? 'gain' : last < base ? 'loss' : 'flat';
  }
}
