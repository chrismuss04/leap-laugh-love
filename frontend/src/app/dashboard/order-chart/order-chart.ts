import { Component, ElementRef, ViewChild, computed, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { formatMoney } from '../../shared/format';
import { MarketSession } from '../../shared/market-session';

/** A price line on the chart. Draggable ones are handles that set the order's price. */
export interface ChartLine {
  id: string;
  label: string;
  value: number;
  tone: 'muted' | 'accent' | 'warning' | 'gain' | 'loss';
  draggable: boolean;
}

/** Room left above and below the data and lines, as a fraction of the range. */
const PADDING = 0.12;
/** The smallest price range drawn, as a fraction of the price, so a flat day isn't blown up. */
const MIN_RANGE = 0.004;

/**
 * The symbol's recent closes with the order's price lines drawn on them, so a limit, stop or trailing
 * stop can be set by dragging its line to where the price should be, and seen against what the stock
 * has been doing. The text boxes beside it stay in step either way, and the lines are keyboard
 * handles too (arrow keys move a cent, Shift a dollar, Page Up/Down one percent).
 *
 * While a line is dragged the price scale is held still, so the line follows the pointer instead of
 * chasing a scale that stretches to include it.
 */
@Component({
  selector: 'app-order-chart',
  imports: [CommonModule],
  template: `
    <div #area class="chart" role="group" [attr.aria-label]="label()" data-testid="order-chart">
      <svg class="series" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        <polyline *ngFor="let segment of segments()" class="series-line" [class.outside]="segment.outside"
                  [attr.points]="segment.points" />
      </svg>
      <p *ngIf="!path()" class="empty">Price history isn’t available right now.</p>

      <div *ngFor="let line of placed(); trackBy: trackById" class="line" [attr.data-tone]="line.tone"
           [style.top.%]="line.y">
        <span class="rule" aria-hidden="true"></span>
        <button *ngIf="line.draggable; else tag" type="button" class="handle" role="slider"
                [class.dragging]="dragging()?.id === line.id"
                [attr.aria-label]="line.label + ' price'" [attr.aria-valuemin]="domain().min.toFixed(2)"
                [attr.aria-valuemax]="domain().max.toFixed(2)" [attr.aria-valuenow]="line.value.toFixed(2)"
                [attr.aria-valuetext]="line.label + ' ' + money(line.value)"
                (pointerdown)="startDrag($event, line)" (pointermove)="drag($event, line)"
                (pointerup)="endDrag($event)" (pointercancel)="endDrag($event)"
                (keydown)="nudge($event, line)">
          <span class="grip" aria-hidden="true"></span>{{ line.label }} <span class="num">{{ money(line.value) }}</span>
        </button>
        <ng-template #tag>
          <span class="tag num">{{ line.label }} {{ money(line.value) }}</span>
        </ng-template>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .chart {
      position: relative; height: 132px; border-radius: var(--radius-md); background: var(--muted);
      overflow: hidden; touch-action: none; user-select: none;
    }
    .series { position: absolute; inset: 0; width: 100%; height: 100%; }
    .series-line {
      fill: none; stroke: var(--muted-foreground); stroke-width: 1.6; vector-effect: non-scaling-stroke;
      stroke-linejoin: round; stroke-linecap: round; opacity: 0.8;
    }
    .series-line.outside { opacity: 0.35; }
    .empty { position: absolute; inset: 0; display: grid; place-items: center; font-size: 12px; color: var(--muted-foreground); }
    .line { position: absolute; left: 0; right: 0; height: 0; --tone: var(--muted-foreground); }
    .line[data-tone='accent'] { --tone: var(--accent, var(--primary)); }
    .line[data-tone='warning'] { --tone: var(--warning); }
    .line[data-tone='gain'] { --tone: var(--gain); }
    .line[data-tone='loss'] { --tone: var(--loss); }
    .rule { position: absolute; left: 0; right: 0; top: 0; border-top: 1px dashed var(--tone); opacity: 0.85; }
    .tag, .handle {
      position: absolute; right: 6px; top: 0; transform: translateY(-50%); white-space: nowrap;
      padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; line-height: 1.5;
      background: var(--surface); color: var(--tone); border: 1px solid var(--tone);
    }
    .tag { opacity: 0.85; }
    .handle { display: inline-flex; align-items: center; gap: 5px; cursor: ns-resize; touch-action: none; }
    .handle:hover, .handle.dragging { background: color-mix(in srgb, var(--tone) 16%, var(--surface)); }
    .handle:focus-visible { outline: 2px solid var(--tone); outline-offset: 2px; }
    .grip {
      width: 6px; height: 8px; border-top: 2px solid currentColor; border-bottom: 2px solid currentColor;
      opacity: 0.7; box-sizing: border-box;
    }
  `]
})
export class OrderChartComponent {
  /** Recent closes, oldest first. */
  readonly points = input<number[]>([]);
  /** The session each close traded in: pre-market and after-hours are drawn fainter. */
  readonly sessions = input<(MarketSession | null | undefined)[] | null>(null);
  /** The lines to draw. */
  readonly lines = input<ChartLine[]>([]);
  readonly label = input('Price chart with order price lines');
  /** A line was dragged or nudged to a new price. */
  readonly lineMove = output<{ id: string; value: number }>();

  @ViewChild('area') area?: ElementRef<HTMLElement>;

  /** The line being dragged, with the price scale it started on. */
  readonly dragging = signal<{ id: string; min: number; max: number } | null>(null);

  money = formatMoney;

  /** The prices the chart spans: the data and every line, with room around them. */
  readonly domain = computed(() => {
    const held = this.dragging();
    if (held) {
      return { min: held.min, max: held.max };
    }
    const values = [...this.points(), ...this.lines().map(line => line.value)].filter(v => Number.isFinite(v));
    if (!values.length) {
      return { min: 0, max: 1 };
    }
    let min = Math.min(...values);
    let max = Math.max(...values);
    const reference = Math.max(Math.abs(max), 1);
    if (max - min < reference * MIN_RANGE) {
      const middle = (max + min) / 2;
      min = middle - reference * MIN_RANGE / 2;
      max = middle + reference * MIN_RANGE / 2;
    }
    const pad = (max - min) * PADDING;
    return { min: min - pad, max: max + pad };
  });

  /** Where a price falls from the top of the chart, 0 to 100. */
  private yOf(value: number): number {
    const { min, max } = this.domain();
    return Math.min(100, Math.max(0, (max - value) / (max - min) * 100));
  }

  readonly path = computed(() => {
    const points = this.points().filter(v => Number.isFinite(v));
    if (points.length < 2) {
      return '';
    }
    return points.map((value, i) => `${(i / (points.length - 1) * 100).toFixed(2)},${this.yOf(value).toFixed(2)}`).join(' ');
  });

  /**
   * The line in runs, each regular hours or outside them (drawn fainter). A close ends the
   * stretch from the close before it, so runs share their end points and the line stays whole.
   */
  readonly segments = computed(() => {
    const coords = this.path().split(' ').filter(Boolean);
    if (coords.length < 2) {
      return [];
    }
    const sessions = this.sessions();
    const outside = (i: number) => sessions?.[i] != null && sessions[i] !== 'REGULAR';
    const runs: { points: string; outside: boolean }[] = [];
    let start = 0;
    for (let i = 2; i <= coords.length; i++) {
      if (i === coords.length || outside(i) !== outside(i - 1)) {
        runs.push({ points: coords.slice(start, i).join(' '), outside: outside(i - 1) });
        start = i - 1;
      }
    }
    return runs;
  });

  readonly placed = computed(() => this.lines().map(line => ({ ...line, y: this.yOf(line.value) })));

  startDrag(event: PointerEvent, line: ChartLine): void {
    if (event.button !== 0 && event.pointerType === 'mouse') {
      return;
    }
    try {
      // Keeps the drag going when the pointer leaves the handle.
      (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
    } catch {
      // No active pointer to capture (e.g. a synthetic event); the handle still follows moves over it.
    }
    const { min, max } = this.domain();
    this.dragging.set({ id: line.id, min, max });
    event.preventDefault();
  }

  drag(event: PointerEvent, line: ChartLine): void {
    const held = this.dragging();
    const area = this.area?.nativeElement;
    if (!held || held.id !== line.id || !area) {
      return;
    }
    const rect = area.getBoundingClientRect();
    if (rect.height <= 0) {
      return;
    }
    const fraction = Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height));
    const value = held.max - fraction * (held.max - held.min);
    this.lineMove.emit({ id: line.id, value: Math.round(value * 100) / 100 });
  }

  endDrag(event: PointerEvent): void {
    try {
      (event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId);
    } catch {
      // Nothing was captured.
    }
    this.dragging.set(null);
  }

  /** Arrow keys move a line a cent (Shift: a dollar), Page Up/Down by one percent. */
  nudge(event: KeyboardEvent, line: ChartLine): void {
    let delta = 0;
    switch (event.key) {
      case 'ArrowUp': delta = event.shiftKey ? 1 : 0.01; break;
      case 'ArrowDown': delta = event.shiftKey ? -1 : -0.01; break;
      case 'PageUp': delta = line.value * 0.01; break;
      case 'PageDown': delta = -line.value * 0.01; break;
      default: return;
    }
    event.preventDefault();
    const value = Math.round((line.value + delta) * 100) / 100;
    if (value > 0) {
      this.lineMove.emit({ id: line.id, value });
    }
  }

  trackById(_: number, line: { id: string }): string {
    return line.id;
  }
}
