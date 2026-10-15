import {
  AfterViewInit, Component, ElementRef, EventEmitter, Input, NgZone, OnChanges, OnDestroy, Output, computed, signal
} from '@angular/core';
import { CommonModule } from '@angular/common';

export interface ChartPoint {
  time: number;
  value: number;
}

/** The drawn line, worked out from the points, the baseline and the chart's size. */
interface ChartShape {
  linePath: string;
  areaPath: string;
  baselineY: number | null;
  min: number;
  max: number;
}

/**
 * Robinhood-style value chart: one line, no axes or gridlines, a dotted baseline at the range's
 * starting value, and scrubbing. Pointer (mouse, pen, touch) or arrow keys pick a point, and the
 * parent shows that point's value in its headline via `scrub`. Drawn as SVG sized in real pixels
 * (via ResizeObserver) so the line keeps a crisp, even stroke at any width.
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
  /** Emits the scrubbed point's index, or null when scrubbing ends. */
  @Output() scrub = new EventEmitter<number | null>();

  readonly width = signal(0);
  readonly active = signal<number | null>(null);

  private readonly padding = 12;
  private observer?: ResizeObserver;
  /** Taken from the host, so the parent sizes the chart with CSS. */
  private readonly hostHeight = signal(280);
  private readonly data = signal<{ points: ChartPoint[]; baseline: number | null }>({ points: [], baseline: null });
  /**
   * Derived rather than assigned, so a resize (which the ResizeObserver reports outside change
   * detection) marks the view dirty instead of changing a bound value behind Angular's back.
   */
  private readonly shape = computed(() => this.build(this.data().points, this.data().baseline));

  get height(): number { return this.hostHeight(); }
  get linePath(): string { return this.shape().linePath; }
  get areaPath(): string { return this.shape().areaPath; }
  get baselineY(): number | null { return this.shape().baselineY; }

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
    this.data.set({ points: this.points, baseline: this.baseline });
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
  }

  x(index: number): number {
    const n = this.points.length;
    return n <= 1 ? this.width() : (index / (n - 1)) * this.width();
  }

  y(value: number): number {
    return this.scale(value, this.shape());
  }

  onPointer(event: PointerEvent): void {
    if (this.points.length === 0 || this.width() === 0) {
      return;
    }
    const rect = this.host.nativeElement.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    this.setActive(Math.round(ratio * (this.points.length - 1)));
  }

  onKey(event: KeyboardEvent): void {
    const last = this.points.length - 1;
    if (last < 0) {
      return;
    }
    const current = this.active() ?? last;
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
    this.setActive(next);
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

  private scale(value: number, { min, max }: Pick<ChartShape, 'min' | 'max'>): number {
    const span = max - min || 1;
    return this.padding + ((max - value) / span) * (this.height - this.padding * 2);
  }

  private build(points: ChartPoint[], baseline: number | null): ChartShape {
    const width = this.width();
    if (points.length === 0 || width === 0) {
      return { linePath: '', areaPath: '', baselineY: null, min: 0, max: 0 };
    }
    const values = points.map(point => point.value);
    if (baseline != null) {
      values.push(baseline);
    }
    const range = { min: Math.min(...values), max: Math.max(...values) };
    const y = (value: number) => this.scale(value, range);
    const x = (index: number) => points.length <= 1 ? width : (index / (points.length - 1)) * width;

    const coords = points.length === 1
      ? [[0, y(points[0].value)], [width, y(points[0].value)]]
      : points.map((point, i) => [x(i), y(point.value)]);
    const linePath = coords.map(([cx, cy], i) => `${i === 0 ? 'M' : 'L'}${cx.toFixed(1)},${cy.toFixed(1)}`).join('');
    return {
      linePath,
      areaPath: `${linePath}L${width},${this.height}L0,${this.height}Z`,
      baselineY: baseline != null ? y(baseline) : null,
      ...range
    };
  }
}
