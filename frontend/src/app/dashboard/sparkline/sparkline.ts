import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MarketSession, highlightedSession, tradedSession } from '../../shared/market-session';

/**
 * A tiny trend line with a dotted baseline, coloured by direction against that baseline. The
 * session under way is drawn at full strength and earlier ones as a softer tint, as on the
 * portfolio chart.
 */
@Component({
    selector: 'app-sparkline',
    imports: [CommonModule],
    template: `
    <svg *ngIf="path || dimmedPath; else empty" [attr.viewBox]="'0 0 ' + width + ' ' + height"
         [attr.width]="width" [attr.height]="height" aria-hidden="true" [attr.data-tone]="tone">
      <line *ngIf="baselineY !== null" x1="0" [attr.x2]="width" [attr.y1]="baselineY" [attr.y2]="baselineY" class="base" />
      <path [attr.d]="path" class="line" />
      <path *ngIf="dimmedPath" [attr.d]="dimmedPath" class="line dimmed" />
    </svg>
    <ng-template #empty><span class="empty" [style.width.px]="width" [style.height.px]="height"></span></ng-template>
  `,
    styles: [`
    :host { display: inline-flex; }
    .line { fill: none; stroke: var(--gain); stroke-width: 1.5; stroke-linejoin: round; vector-effect: non-scaling-stroke; }
    svg[data-tone='loss'] .line { stroke: var(--loss); }
    svg[data-tone='flat'] .line { stroke: var(--muted-foreground); }
    .line.dimmed { opacity: 0.45; }
    .base { stroke: var(--muted-foreground); stroke-dasharray: 1 3; stroke-width: 1; opacity: 0.5; vector-effect: non-scaling-stroke; }
    .empty { display: inline-block; }
  `]
})
export class SparklineComponent implements OnChanges {
  @Input() values: number[] = [];
  /** The value the trend is judged against, e.g. the previous close. Defaults to the first value. */
  @Input() baseline: number | null = null;
  /** The session of each value, which the stretch from the value before up to it traded in. */
  @Input() sessions: (MarketSession | null | undefined)[] | null = null;
  /**
   * The session under way, drawn at full strength; the stretches in other sessions are tinted.
   * While the market is closed, or until it's known, the last session traded in is.
   */
  @Input() highlight: MarketSession | null = null;
  @Input() width = 88;
  @Input() height = 32;

  path = '';
  /** The stretches in other sessions than the highlighted one, drawn as a softer tint. */
  dimmedPath = '';
  baselineY: number | null = null;
  tone: 'gain' | 'loss' | 'flat' = 'flat';

  ngOnChanges(): void {
    const values = this.values;
    if (values.length < 2) {
      this.path = '';
      this.dimmedPath = '';
      return;
    }
    const base = this.baseline ?? values[0];
    const min = Math.min(base, ...values);
    const max = Math.max(base, ...values);
    const span = max - min || 1;
    const pad = 2;
    const y = (v: number) => pad + ((max - v) / span) * (this.height - pad * 2);
    const point = (i: number) => `${((i / (values.length - 1)) * this.width).toFixed(1)},${y(values[i]).toFixed(1)}`;
    // Each segment, from value i - 1 to i, goes to the full-strength or tinted path; a run starts a
    // new subpath.
    const sessions = this.sessions ?? [];
    const highlight = this.sessions ? highlightedSession(this.highlight, sessions) : null;
    let bright = '';
    let dimmed = '';
    let previous: boolean | null = null;
    for (let i = 1; i < values.length; i++) {
      const isDimmed = highlight !== null && tradedSession(sessions, i) !== highlight;
      const segment = `${isDimmed === previous ? '' : `M${point(i - 1)}`}L${point(i)}`;
      if (isDimmed) {
        dimmed += segment;
      } else {
        bright += segment;
      }
      previous = isDimmed;
    }
    this.path = bright;
    this.dimmedPath = dimmed;
    this.baselineY = y(base);
    const last = values[values.length - 1];
    this.tone = last > base ? 'gain' : last < base ? 'loss' : 'flat';
  }
}
