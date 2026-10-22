import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MarketSession, highlightStart, highlightedSession } from '../../shared/market-session';
import { SPARKLINE_LEAD_IN_WIDTH, TradingHours, dayFrame, firstInDomain, placeByTime } from '../../shared/chart-layout';

/**
 * A tiny trend line with a dotted baseline, coloured by direction against that baseline. The
 * stretch at the end in the session under way is drawn at full strength and the rest as a softer
 * tint, as on the portfolio chart. Given each value's time and the day's hours, it frames the day
 * as the 1D chart does: by time, the rest of the day left empty, and in pre-market led into by the
 * previous session's last hour.
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
  /** When each value was made (epoch ms), to lay the day out by time; null spaces them evenly. */
  @Input() times: number[] | null = null;
  /** The hours of the trading day under way, which frame the day; null spaces values evenly. */
  @Input() dayHours: TradingHours | null = null;
  @Input() width = 88;
  @Input() height = 32;

  path = '';
  /** The stretches in other sessions than the highlighted one, drawn as a softer tint. */
  dimmedPath = '';
  baselineY: number | null = null;
  tone: 'gain' | 'loss' | 'flat' = 'flat';

  ngOnChanges(): void {
    const values = this.values;
    const xs = this.place();
    const drawn = values.flatMap((_, i) => xs[i] === null ? [] : [i]);
    if (drawn.length < 2) {
      this.path = '';
      this.dimmedPath = '';
      return;
    }
    const base = this.baseline ?? values[drawn[0]];
    const shown = drawn.map(i => values[i]);
    const min = Math.min(base, ...shown);
    const max = Math.max(base, ...shown);
    const span = max - min || 1;
    const pad = 2;
    const y = (v: number) => pad + ((max - v) / span) * (this.height - pad * 2);
    const point = (i: number) => `${xs[i]!.toFixed(1)},${y(values[i]).toFixed(1)}`;
    // Only the stretch at the end in the session under way is at full strength, never a lead-in.
    const sessions = this.sessions ?? [];
    const brightFrom = Math.max(
      this.sessions ? highlightStart(sessions, highlightedSession(this.highlight, sessions)) : 0,
      firstInDomain(this.times ?? [], this.domain()));
    // Each segment, from one drawn value to the next, goes to the full-strength or tinted path; a
    // run starts a new subpath.
    let bright = '';
    let dimmed = '';
    let previous: boolean | null = null;
    for (let k = 1; k < drawn.length; k++) {
      const i = drawn[k];
      const isDimmed = i < brightFrom;
      const segment = `${isDimmed === previous ? '' : `M${point(drawn[k - 1])}`}L${point(i)}`;
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
    const last = values[drawn[drawn.length - 1]];
    this.tone = last > base ? 'gain' : last < base ? 'loss' : 'flat';
  }

  /** The day this sparkline frames, given each value's time and the day's hours. */
  private domain() {
    return this.times && this.dayHours && this.times.length === this.values.length
      ? dayFrame(this.dayHours, this.highlight, this.times, Date.now())
      : null;
  }

  /** Each value's x: by time across the day, or evenly spaced. */
  private place(): (number | null)[] {
    const domain = this.domain();
    if (domain) {
      return placeByTime(this.times!, domain, this.width, SPARKLINE_LEAD_IN_WIDTH);
    }
    return this.values.map((_, i) => (i / Math.max(1, this.values.length - 1)) * this.width);
  }
}
