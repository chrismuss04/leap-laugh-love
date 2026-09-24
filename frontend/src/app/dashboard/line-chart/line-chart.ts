import {
  AfterViewInit, Component, ElementRef, EventEmitter, Input, NgZone, OnChanges, OnDestroy, Output, signal
} from '@angular/core';
import { CommonModule } from '@angular/common';

export interface ChartPoint {
  time: number;
  value: number;
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
  /** Taken from the host, so the parent sizes the chart with CSS. */
  height = 280;
  readonly active = signal<number | null>(null);

  linePath = '';
  areaPath = '';
  baselineY: number | null = null;

  private readonly padding = 12;
  private observer?: ResizeObserver;
  private min = 0;
  private max = 0;

  constructor(private host: ElementRef<HTMLElement>, private zone: NgZone) {}

  ngAfterViewInit(): void {
    this.observer = new ResizeObserver(entries => {
      const width = Math.round(entries[0].contentRect.width);
      const height = Math.round(entries[0].contentRect.height) || this.height;
      if (width !== this.width() || height !== this.height) {
        // ResizeObserver callbacks aren't guaranteed to run inside Angular's zone.
        this.zone.run(() => {
          this.height = height;
          this.width.set(width);
          this.build();
        });
      }
    });
    this.observer.observe(this.host.nativeElement);
  }

  ngOnChanges(): void {
    if (this.active() !== null && this.active()! >= this.points.length) {
      this.clear();
    }
    this.build();
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
  }

  x(index: number): number {
    const n = this.points.length;
    return n <= 1 ? this.width() : (index / (n - 1)) * this.width();
  }

  y(value: number): number {
    const span = this.max - this.min || 1;
    return this.padding + ((this.max - value) / span) * (this.height - this.padding * 2);
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

  private build(): void {
    const points = this.points;
    if (points.length === 0 || this.width() === 0) {
      this.linePath = this.areaPath = '';
      this.baselineY = null;
      return;
    }
    const values = points.map(point => point.value);
    if (this.baseline != null) {
      values.push(this.baseline);
    }
    this.min = Math.min(...values);
    this.max = Math.max(...values);

    const coords = points.length === 1
      ? [[0, this.y(points[0].value)], [this.width(), this.y(points[0].value)]]
      : points.map((point, i) => [this.x(i), this.y(point.value)]);
    this.linePath = coords.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join('');
    this.areaPath = `${this.linePath}L${this.width()},${this.height}L0,${this.height}Z`;
    this.baselineY = this.baseline != null ? this.y(this.baseline) : null;
  }
}
