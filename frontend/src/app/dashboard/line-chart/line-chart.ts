import {
  AfterViewInit, Component, ElementRef, EventEmitter, Input, NgZone, OnChanges, OnDestroy, Output, computed, signal
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MarketSession } from '../../shared/market-session';

export interface ChartPoint {
  time: number;
  value: number;
  /**
   * The session of the stretch the point closes (from the point before up to it). Stretches in
   * other sessions than the highlighted one are drawn as a softer tint of the line. Leave it out
   * to count the point as regular hours.
   */
  session?: MarketSession;
}

/**
 * The stretch of time the chart spans, in epoch ms, when it's laid out by time rather than point
 * by point: points before it aren't drawn, and time after the last point is left empty.
 */
export interface ChartDomain {
  start: number;
  end: number;
}

/**
 * A colour stop across the line's width: full strength in the highlighted session, a soft tint in
 * the others, and a brief brighter glint where the line comes into the highlight.
 */
export interface ChartStop {
  /** 0-1 across the width. */
  offset: number;
  kind: 'dim' | 'bright' | 'glint';
}

/** The drawn line, worked out from the points, the baseline and the chart's size. */
interface ChartShape {
  linePath: string;
  areaPath: string;
  baselineY: number | null;
  min: number;
  max: number;
  /** Each point's x, or null for one that isn't drawn (before the domain starts). */
  xs: (number | null)[];
  /** The indexes of the points drawn, oldest first. */
  drawn: number[];
  /** Where the line changes between the highlighted session and others, feathered so it blends. */
  stops: ChartStop[];
  /** The same for the fill under the line, feathered wider so it has no visible edge. */
  fadeStops: ChartStop[];
}

/** How far, in pixels, the line takes to blend into or out of the highlighted session. */
const FEATHER = 64;
/** How far the fill under the line takes to blend: wider, as a soft wash shows a seam sooner. */
const FILL_FEATHER = 48;

let nextChartId = 0;

/**
 * Robinhood-style value chart: one line, no axes or gridlines, a dotted baseline at the range's
 * starting value, and scrubbing. Pointer (mouse, pen, touch) or arrow keys pick a point, and the
 * parent shows that point's value in its headline via `scrub`. Drawn as SVG sized in real pixels
 * (via ResizeObserver) so the line keeps a crisp, even stroke at any width.
 *
 * One session is highlighted - the one under way, as Robinhood does - and the others are a softer
 * tint of the same line, which glints as it comes into the highlight rather than switching colour.
 * Given a `domain`, points are placed by time, so a day still under way leaves the rest of the day
 * empty to the right.
 */
@Component({
    selector: 'app-line-chart',
    imports: [CommonModule],
    templateUrl: './line-chart.html',
    styleUrl: './line-chart.css'
})
export class LineChartComponent implements OnChanges, AfterViewInit, OnDestroy {
  @Input() points: ChartPoint[] = [];
  @Input() baseline: number | null = null;
  @Input() tone: 'gain' | 'loss' | 'flat' = 'gain';
  @Input() label = 'Portfolio value chart';
  /** Lays the points out by time across this span; null spaces them evenly. */
  @Input() domain: ChartDomain | null = null;
  /** The session drawn at full strength; the others are tinted. Null highlights regular hours. */
  @Input() highlight: MarketSession | null = null;
  /** Emits the scrubbed point's index, or null when scrubbing ends. */
  @Output() scrub = new EventEmitter<number | null>();

  readonly width = signal(0);
  readonly active = signal<number | null>(null);
  /** Unique per chart, for its gradients and mask. */
  readonly uid = `line-chart-${nextChartId++}`;

  private readonly padding = 12;
  private observer?: ResizeObserver;
  /** Taken from the host, so the parent sizes the chart with CSS. */
  private readonly hostHeight = signal(280);
  private readonly data = signal<{
    points: ChartPoint[]; baseline: number | null; domain: ChartDomain | null; highlight: MarketSession
  }>({ points: [], baseline: null, domain: null, highlight: 'REGULAR' });
  /**
   * Derived rather than assigned, so a resize (which the ResizeObserver reports outside change
   * detection) marks the view dirty instead of changing a bound value behind Angular's back.
   */
  private readonly shape = computed(() => this.build(this.data()));

  get height(): number { return this.hostHeight(); }
  get linePath(): string { return this.shape().linePath; }
  get areaPath(): string { return this.shape().areaPath; }
  get baselineY(): number | null { return this.shape().baselineY; }
  get stops(): ChartStop[] { return this.shape().stops; }
  get fadeStops(): ChartStop[] { return this.shape().fadeStops; }
  /** Whether any of the line is tinted, being in another session than the highlighted one. */
  get hasDimmed(): boolean { return this.shape().stops.some(stop => stop.kind === 'dim'); }
  /** The newest point drawn: where the live dot sits. */
  get lastDrawn(): number | null { return this.shape().drawn.at(-1) ?? null; }

  constructor(private host: ElementRef<HTMLElement>, private zone: NgZone) {}

  ngAfterViewInit(): void {
    this.observer = new ResizeObserver(entries => {
      const width = Math.round(entries[0].contentRect.width);
      const height = Math.round(entries[0].contentRect.height) || this.height;
      if (width !== this.width() || height !== this.height) {
        // ResizeObserver callbacks aren't guaranteed to run inside Angular's zone.
        this.zone.run(() => {
          this.hostHeight.set(height);
          this.width.set(width);
        });
      }
    });
    this.observer.observe(this.host.nativeElement);
  }

  ngOnChanges(): void {
    if (this.active() !== null && this.active()! >= this.points.length) {
      this.clear();
    }
    this.data.set({ points: this.points, baseline: this.baseline, domain: this.domain, highlight: this.highlight ?? 'REGULAR' });
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
  }

  x(index: number): number {
    return this.shape().xs[index] ?? 0;
  }

  y(value: number): number {
    return this.scale(value, this.shape());
  }

  /** Whether a point closes a stretch in another session than the highlighted one, drawn tinted. */
  dimmed(index: number): boolean {
    return (this.points[index]?.session ?? 'REGULAR') !== this.data().highlight;
  }

  /** Whether the market is closed at the live point, so the dot stops pulsing. */
  get closedNow(): boolean {
    return this.points.at(-1)?.session === 'CLOSED';
  }

  onPointer(event: PointerEvent): void {
    const drawn = this.shape().drawn;
    if (drawn.length === 0 || this.width() === 0) {
      return;
    }
    const rect = this.host.nativeElement.getBoundingClientRect();
    const at = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width)) * this.width();
    this.setActive(this.nearest(at, drawn));
  }

  onKey(event: KeyboardEvent): void {
    const drawn = this.shape().drawn;
    if (drawn.length === 0) {
      return;
    }
    const last = drawn.length - 1;
    const current = Math.max(0, drawn.indexOf(this.active() ?? drawn[last]));
    const step = event.shiftKey ? 10 : 1;
    let next: number | null = null;
    switch (event.key) {
      case 'ArrowLeft': next = Math.max(0, current - step); break;
      case 'ArrowRight': next = Math.min(last, current + step); break;
      case 'Home': next = 0; break;
      case 'End': next = last; break;
      case 'Escape': this.clear(); return;
      default: return;
    }
    event.preventDefault();
    this.setActive(drawn[next]);
  }

  clear(): void {
    if (this.active() !== null) {
      this.active.set(null);
      this.scrub.emit(null);
    }
  }

  private setActive(index: number): void {
    if (index !== this.active()) {
      this.active.set(index);
      this.scrub.emit(index);
    }
  }

  /** The drawn point nearest an x position (the later one when halfway): past the newest, the newest. */
  private nearest(at: number, drawn: number[]): number {
    const xs = this.shape().xs;
    let best = drawn[0];
    for (const index of drawn) {
      if (Math.abs(xs[index]! - at) <= Math.abs(xs[best]! - at)) {
        best = index;
      }
    }
    return best;
  }

  private scale(value: number, { min, max }: Pick<ChartShape, 'min' | 'max'>): number {
    const span = max - min || 1;
    return this.padding + ((max - value) / span) * (this.height - this.padding * 2);
  }

  private build({ points, baseline, domain }: { points: ChartPoint[]; baseline: number | null; domain: ChartDomain | null }): ChartShape {
    const width = this.width();
    const empty: ChartShape = {
      linePath: '', areaPath: '', baselineY: null, min: 0, max: 0, xs: [], drawn: [], stops: [], fadeStops: []
    };
    if (points.length === 0 || width === 0) {
      return empty;
    }
    const xs = this.place(points, domain, width);
    const drawn = xs.flatMap((x, i) => x === null ? [] : [i]);
    if (drawn.length === 0) {
      return { ...empty, xs };
    }
    const values = drawn.map(i => points[i].value);
    if (baseline != null) {
      values.push(baseline);
    }
    const range = { min: Math.min(...values), max: Math.max(...values) };
    const y = (value: number) => this.scale(value, range);

    // A lone point (evenly spaced) is a flat line across the chart.
    const coords = drawn.length === 1 && !domain
      ? [[0, y(points[0].value)], [width, y(points[0].value)]]
      : drawn.map(i => [xs[i]!, y(points[i].value)]);
    const linePath = coords.map(([cx, cy], i) => `${i === 0 ? 'M' : 'L'}${cx.toFixed(1)},${cy.toFixed(1)}`).join('');
    const first = coords[0][0];
    const last = coords[coords.length - 1][0];
    return {
      linePath,
      areaPath: `${linePath}L${last.toFixed(1)},${this.height}L${first.toFixed(1)},${this.height}Z`,
      baselineY: baseline != null ? y(baseline) : null,
      ...range,
      xs,
      drawn,
      stops: this.colourStops(xs, drawn, width, FEATHER, true),
      fadeStops: this.colourStops(xs, drawn, width, FILL_FEATHER)
    };
  }

  /**
   * Each point's x: by time across the domain (null before it; anything after it, such as a
   * live point while the market is closed, sits at its end), or evenly spaced without one.
   */
  private place(points: ChartPoint[], domain: ChartDomain | null, width: number): (number | null)[] {
    if (!domain) {
      return points.map((_, i) => points.length <= 1 ? width : (i / (points.length - 1)) * width);
    }
    const span = domain.end - domain.start || 1;
    return points.map(point => point.time < domain.start
      ? null
      : Math.min(1, (point.time - domain.start) / span) * width);
  }

  /**
   * Stops for a gradient across the width that tints the line outside the highlighted session. A
   * drawn point closes the stretch from the drawn point before it, so each boundary falls on a
   * point, and the line blends across it instead of switching colour at a hard edge - catching a
   * brighter glint on the way into the highlight, when `glint` is set.
   */
  private colourStops(xs: (number | null)[], drawn: number[], width: number, feather: number, glint = false): ChartStop[] {
    const kind = (i: number): ChartStop['kind'] => this.dimmed(i) ? 'dim' : 'bright';
    const offset = (x: number) => Math.min(width, Math.max(0, x)) / width;
    const stops: ChartStop[] = [{ offset: 0, kind: kind(drawn.length > 1 ? drawn[1] : drawn[0]) }];
    for (let k = 2; k < drawn.length; k++) {
      const before = kind(drawn[k - 1]);
      const after = kind(drawn[k]);
      if (before !== after) {
        const at = xs[drawn[k - 1]]!;
        stops.push({ offset: offset(at - feather / 2), kind: before });
        if (glint && after === 'bright') {
          stops.push({ offset: offset(at + feather / 6), kind: 'glint' });
        }
        stops.push({ offset: offset(at + feather / 2), kind: after });
      }
    }
    stops.push({ offset: 1, kind: stops[stops.length - 1].kind });
    return stops;
  }
}
