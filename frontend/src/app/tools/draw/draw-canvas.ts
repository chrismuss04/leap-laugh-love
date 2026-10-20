import {
  AfterViewInit, Component, ElementRef, NgZone, OnDestroy, computed, effect, inject, input, output, signal, untracked, viewChild
} from '@angular/core';
import { formatMoney } from '../../shared/format';
import { TradeIdea } from './interpret';
import {
  ChartFrame, DataPoint, PixelPoint, extendStroke, niceTicks, priceRange, resample, smooth, splinePath, thin, toData, toPixel
} from './stroke';

/** A price level the client can drag: the entry of a limit order, a take profit, or a stop loss. */
export type LevelKey = 'entry' | 'target' | 'stop';

export interface LevelChange {
  level: LevelKey;
  price: number;
}

/** One level as drawn: a line across the future and a tag on it that drags. */
interface LevelView {
  key: LevelKey;
  label: string;
  price: number;
  y: number;
  tone: 'gain' | 'loss' | 'entry' | 'muted';
  draggable: boolean;
}

interface Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  life: number;
  max: number;
  size: number;
  color: string;
}

/** Room on the right for the price axis, and above and below the plot. */
const AXIS = 64;
const TOP = 20;
const BOTTOM = 30;
/** A stroke shorter than this many pixels across is a tap, not a drawing. */
const MIN_STROKE = 24;
/** Prices around the chart's range are padded this much, so there's room to draw past them. */
const RANGE_PADDING = 0.45;

let nextId = 0;

const cents = (value: number) => Math.round(value * 100) / 100;

/** Keeps a drag on its element when the pointer strays; a pointer that has already gone can't be held. */
function capture(event: PointerEvent, hold: boolean): void {
  const target = event.currentTarget as HTMLElement;
  try {
    if (hold) {
      target.setPointerCapture(event.pointerId);
    } else {
      target.releasePointerCapture(event.pointerId);
    }
  } catch {
    // NotFoundError: the pointer is no longer active.
  }
}

/**
 * Draw Your Trade: the chart the client draws on. History runs up to a live dot at now; the space
 * to its right is the future, and dragging from the dot into it draws where the client thinks the
 * price goes. While drawing, the ink glows and sparks on a canvas over the chart - green above
 * today's price, red below - and moving back over the line rubs it out. On release the stroke
 * settles into a smooth curve and `drawn` emits it as prices by step.
 *
 * Given an idea, it draws the idea's levels across the future with a tag on each; a tag drags, or
 * takes the arrow keys, to move its level, and `levelChange` asks the parent to apply it.
 */
@Component({
  selector: 'app-draw-canvas',
  template: `
    <div #surface class="surface" [class.drawing]="drawing()" [class.empty]="!settled() && !drawing()"
         [class.disabled]="disabled()" data-testid="draw-surface" tabindex="0"
         role="application" [attr.aria-label]="ariaLabel()"
         (pointerdown)="down($event)" (pointermove)="move($event)" (pointerup)="up($event)"
         (pointercancel)="up($event)" (pointerleave)="hover.set(null)" (keydown.escape)="clear()">
      @if (frame(); as f) {
        <svg [attr.width]="size().width" [attr.height]="size().height" aria-hidden="true">
          <defs>
            <linearGradient [attr.id]="ids.history" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" class="history-top" />
              <stop offset="100%" class="history-bottom" />
            </linearGradient>
            <linearGradient [attr.id]="ids.future" x1="0" y1="0" x2="1" y2="0">
              <stop offset="0%" class="future-start" />
              <stop offset="100%" class="future-end" />
            </linearGradient>
            <linearGradient [attr.id]="ids.ink" gradientUnits="userSpaceOnUse" x1="0" [attr.y1]="f.top" x2="0" [attr.y2]="f.bottom">
              <stop [attr.offset]="nowOffset()" class="ink-gain" />
              <stop [attr.offset]="nowOffset()" class="ink-loss" />
            </linearGradient>
            <clipPath [attr.id]="ids.above"><rect x="0" [attr.y]="f.top - 40" [attr.width]="size().width" [attr.height]="now().y - f.top + 40" /></clipPath>
            <clipPath [attr.id]="ids.below"><rect x="0" [attr.y]="now().y" [attr.width]="size().width" [attr.height]="f.bottom - now().y + 40" /></clipPath>
            <filter [attr.id]="ids.glow" x="-20%" y="-50%" width="140%" height="200%">
              <feGaussianBlur stdDeviation="5" result="blur" />
              <feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge>
            </filter>
          </defs>

          <rect class="future" [attr.x]="now().x" [attr.y]="f.top - 8" [attr.width]="f.right - now().x"
                [attr.height]="f.bottom - f.top + 16" rx="10" [attr.fill]="'url(#' + ids.future + ')'" />

          @for (tick of priceTicks(); track tick.price) {
            <line class="grid" x1="0" [attr.x2]="f.right" [attr.y1]="tick.y" [attr.y2]="tick.y" />
            <text class="axis" [attr.x]="f.right + 10" [attr.y]="tick.y" dominant-baseline="middle">{{ tick.label }}</text>
          }
          @for (tick of timeTicks(); track tick.step) {
            <line class="grid time" [attr.x1]="tick.x" [attr.x2]="tick.x" [attr.y1]="f.top" [attr.y2]="f.bottom" />
            <text class="axis time" [attr.x]="tick.x" [attr.y]="f.bottom + 20" text-anchor="middle">{{ tick.label }}</text>
          }

          <line class="now-line" [attr.x1]="now().x" [attr.x2]="now().x" [attr.y1]="f.top - 8" [attr.y2]="f.bottom + 6" />
          <line class="now-level" [attr.x1]="now().x" [attr.x2]="f.right" [attr.y1]="now().y" [attr.y2]="now().y" />

          @for (key of historyKey(); track key) {
            <path class="history-area" [attr.d]="historyArea()" [attr.fill]="'url(#' + ids.history + ')'" />
            <path class="history-line" [attr.d]="historyPath()" pathLength="1" />
          }

          @if (!settled() && !drawing()) {
            <path class="ghost" [attr.d]="ghostPath()" pathLength="1" />
          }

          @if (settled()) {
            @for (key of settleKey(); track key) {
              <g class="settled">
                <path class="fill gain" [attr.d]="settledFill()" [attr.clip-path]="'url(#' + ids.above + ')'" />
                <path class="fill loss" [attr.d]="settledFill()" [attr.clip-path]="'url(#' + ids.below + ')'" />
                <path class="drawn" [attr.d]="settledPath()" [attr.stroke]="'url(#' + ids.ink + ')'" [attr.filter]="'url(#' + ids.glow + ')'" />
                <path class="shimmer" [attr.d]="settledPath()" pathLength="1" />
              </g>
            }
          }

          @for (level of levels(); track level.key) {
            <line class="level" [attr.data-tone]="level.tone" [attr.x1]="now().x" [attr.x2]="f.right"
                  [attr.y1]="level.y" [attr.y2]="level.y" />
          }
          @for (marker of markers(); track marker.key) {
            <circle class="marker-ring" [attr.data-tone]="marker.tone" [attr.cx]="marker.x" [attr.cy]="marker.y" r="11" />
            <circle class="marker" [attr.data-tone]="marker.tone" [attr.cx]="marker.x" [attr.cy]="marker.y" r="4.5" />
          }

          @if (hover(); as h) {
            <line class="crosshair" [attr.x1]="now().x" [attr.x2]="f.right" [attr.y1]="h.y" [attr.y2]="h.y" />
            <line class="crosshair" [attr.x1]="h.x" [attr.x2]="h.x" [attr.y1]="f.top" [attr.y2]="f.bottom" />
          }

          <circle class="now-halo" [attr.cx]="now().x" [attr.cy]="now().y" r="14" />
          <circle class="now-dot" [attr.cx]="now().x" [attr.cy]="now().y" r="5.5" />
        </svg>

        <canvas #ink class="ink" aria-hidden="true"></canvas>

        @if (!settled() && !drawing()) {
          <p class="hint" [style.left.px]="now().x + 24" [style.right.px]="axis" data-testid="draw-hint">
            Draw where you think <strong>{{ symbol() }}</strong> goes
            <span class="arrow" aria-hidden="true">→</span>
          </p>
        }

        @if (hover(); as h) {
          <span class="tag price" [style.top.px]="h.y" [style.left.px]="f.right + 4" data-testid="hover-price">{{ h.price }}</span>
          <span class="tag time" [style.top.px]="f.bottom + 4" [style.left.px]="h.x">{{ h.time }}</span>
        }

        @for (level of levels(); track level.key) {
          <span class="level-tag" [attr.data-tone]="level.tone" [attr.data-level]="level.key" [style.top.px]="level.y"
                [style.right.px]="axis + 8" [class.draggable]="level.draggable"
                [attr.role]="level.draggable ? 'slider' : null" [attr.tabindex]="level.draggable ? 0 : null"
                [attr.aria-label]="level.label" [attr.aria-valuenow]="level.draggable ? level.price : null"
                [attr.aria-valuetext]="level.draggable ? money(level.price) : null"
                (pointerdown)="grab($event, level)" (pointermove)="drag($event, level)"
                (pointerup)="release($event)" (pointercancel)="release($event)" (keydown)="nudge($event, level)">
            @if (level.draggable) { <span class="grip" aria-hidden="true">⋮⋮</span> }
            <span class="what">{{ level.label }}</span>
            <span class="num">{{ money(level.price) }}</span>
          </span>
        }
      }
    </div>
  `,
  styles: [`
    :host { display: block; height: 400px; }
    @media (max-width: 640px) { :host { height: 320px; } }
    .surface {
      --tone-gain: var(--gain);
      --tone-loss: var(--loss);
      position: relative;
      width: 100%;
      height: 100%;
      touch-action: none;
      user-select: none;
      -webkit-user-select: none;
      cursor: crosshair;
      outline: none;
      border-radius: var(--radius-lg);
    }
    .surface.disabled { pointer-events: none; opacity: 0.6; }
    .surface:focus-visible { box-shadow: 0 0 0 2px color-mix(in srgb, var(--primary) 45%, transparent); }
    svg { position: absolute; inset: 0; display: block; overflow: visible; }
    canvas.ink { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }

    .future-start { stop-color: var(--primary); stop-opacity: 0.07; }
    .future-end { stop-color: var(--primary); stop-opacity: 0.01; }
    .drawing .future-start { stop-opacity: 0.12; }
    .future { transition: opacity 0.3s ease; }

    .grid { stroke: var(--border); stroke-width: 1; opacity: 0.55; }
    .grid.time { stroke-dasharray: 2 6; opacity: 0.8; }
    .axis { fill: var(--muted-foreground); font-size: 11px; font-variant-numeric: tabular-nums; }

    .now-line { stroke: var(--muted-foreground); stroke-width: 1; stroke-dasharray: 3 4; opacity: 0.6; }
    .now-level { stroke: var(--muted-foreground); stroke-width: 1; stroke-dasharray: 1 5; opacity: 0.5; }

    .history-top { stop-color: var(--foreground); stop-opacity: 0.1; }
    .history-bottom { stop-color: var(--foreground); stop-opacity: 0; }
    .history-line {
      fill: none;
      stroke: color-mix(in srgb, var(--foreground) 72%, transparent);
      stroke-width: 1.75;
      stroke-linejoin: round;
      stroke-linecap: round;
      stroke-dasharray: 1;
      animation: draw-in 1.1s cubic-bezier(0.2, 0, 0, 1) both;
    }
    .history-area { animation: fade 1.1s ease both; }

    .ghost {
      fill: none;
      stroke: var(--primary);
      stroke-width: 2.5;
      stroke-linecap: round;
      stroke-dasharray: 0.35 1;
      opacity: 0.5;
      animation: ghost 3.2s cubic-bezier(0.45, 0, 0.2, 1) 0.9s infinite;
    }

    .ink-gain { stop-color: var(--tone-gain); }
    .ink-loss { stop-color: var(--tone-loss); }
    .settled { animation: fade 0.35s ease both; }
    .drawn { fill: none; stroke-width: 3; stroke-linecap: round; stroke-linejoin: round; }
    .fill { stroke: none; opacity: 0.14; }
    .fill.gain { fill: var(--tone-gain); }
    .fill.loss { fill: var(--tone-loss); }
    .shimmer {
      fill: none;
      stroke: #fff;
      stroke-width: 3;
      stroke-linecap: round;
      stroke-dasharray: 0.06 1.2;
      stroke-dashoffset: 0.06;
      opacity: 0;
      animation: shimmer 0.9s cubic-bezier(0.4, 0, 0.2, 1) 0.15s both;
    }

    .level { stroke-width: 1.5; stroke-dasharray: 6 5; animation: fade 0.4s ease both; }
    .level[data-tone='gain'] { stroke: var(--gain); }
    .level[data-tone='loss'] { stroke: var(--loss); }
    .level[data-tone='entry'] { stroke: var(--primary); }
    .level[data-tone='muted'] { stroke: var(--muted-foreground); }
    .marker, .marker-ring { --marker: var(--primary); }
    [data-tone='gain'] { --marker: var(--gain); }
    [data-tone='loss'] { --marker: var(--loss); }
    .marker { fill: var(--marker); stroke: var(--background); stroke-width: 2; }
    .marker-ring {
      fill: none;
      stroke: var(--marker);
      stroke-width: 1.5;
      animation: ring 2.4s ease-out infinite;
      transform-box: fill-box;
      transform-origin: center;
    }

    .crosshair { stroke: var(--muted-foreground); stroke-width: 1; stroke-dasharray: 2 3; opacity: 0.5; pointer-events: none; }

    .now-dot { fill: var(--primary); stroke: var(--background); stroke-width: 2; }
    .now-halo { fill: var(--primary); opacity: 0.18; animation: halo 2.2s ease-in-out infinite; transform-box: fill-box; transform-origin: center; }
    .drawing .now-halo { animation: none; opacity: 0.28; }

    .hint {
      position: absolute;
      top: 38%;
      margin: 0;
      text-align: center;
      font-size: 15px;
      color: var(--muted-foreground);
      pointer-events: none;
      animation: fade 0.6s ease 0.6s both;
    }
    .hint strong { color: var(--foreground); font-weight: 600; }
    .hint .arrow { display: inline-block; margin-left: 4px; color: var(--primary); animation: nudge 1.6s ease-in-out infinite; }

    .tag {
      position: absolute;
      transform: translateY(-50%);
      padding: 2px 6px;
      border-radius: 6px;
      background: var(--foreground);
      color: var(--background);
      font-size: 11px;
      font-weight: 600;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
      pointer-events: none;
    }
    .tag.time { transform: translateX(-50%); }

    .level-tag {
      position: absolute;
      transform: translateY(-50%);
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 999px;
      border: 1px solid currentColor;
      background: color-mix(in srgb, var(--card) 88%, transparent);
      backdrop-filter: blur(6px);
      font-size: 12px;
      font-weight: 600;
      white-space: nowrap;
      cursor: default;
      animation: pop 0.35s cubic-bezier(0.2, 0.9, 0.3, 1.3) both;
      touch-action: none;
    }
    .level-tag.draggable { cursor: ns-resize; }
    .level-tag.draggable:hover, .level-tag.draggable:focus-visible {
      outline: none;
      box-shadow: 0 0 0 4px color-mix(in srgb, currentColor 22%, transparent);
    }
    .level-tag[data-tone='gain'] { color: var(--gain); }
    .level-tag[data-tone='loss'] { color: var(--loss); }
    .level-tag[data-tone='entry'] { color: var(--primary); }
    .level-tag[data-tone='muted'] { color: var(--muted-foreground); }
    .level-tag .what { color: var(--foreground); font-weight: 500; }
    .level-tag .num { font-variant-numeric: tabular-nums; }
    .grip { font-size: 10px; letter-spacing: -2px; opacity: 0.7; }

    @keyframes draw-in { from { stroke-dashoffset: 1; } to { stroke-dashoffset: 0; } }
    @keyframes fade { from { opacity: 0; } to { opacity: 1; } }
    @keyframes ghost {
      0% { stroke-dashoffset: 0.35; opacity: 0; }
      15% { opacity: 0.5; }
      70% { stroke-dashoffset: -0.65; opacity: 0.5; }
      100% { stroke-dashoffset: -0.65; opacity: 0; }
    }
    @keyframes shimmer {
      0% { stroke-dashoffset: 0.06; opacity: 0.9; }
      90% { opacity: 0.9; }
      100% { stroke-dashoffset: -1.1; opacity: 0; }
    }
    @keyframes halo { 0%, 100% { transform: scale(0.7); opacity: 0.25; } 50% { transform: scale(1.25); opacity: 0.08; } }
    @keyframes ring { 0% { transform: scale(0.5); opacity: 0.7; } 100% { transform: scale(1.6); opacity: 0; } }
    @keyframes nudge { 0%, 100% { transform: translateX(0); } 50% { transform: translateX(5px); } }
    @keyframes pop { from { opacity: 0; transform: translateY(-50%) scale(0.85); } to { opacity: 1; transform: translateY(-50%) scale(1); } }

    @media (prefers-reduced-motion: reduce) {
      .history-line, .history-area, .settled, .level, .hint, .level-tag { animation: none; }
      .ghost { animation: none; stroke-dasharray: none; opacity: 0.3; }
      .shimmer, .marker-ring { display: none; }
      .now-halo, .hint .arrow { animation: none; }
    }
  `]
})
export class DrawCanvasComponent implements AfterViewInit, OnDestroy {
  /** The symbol being drawn, for the hint and labels. */
  readonly symbol = input('');
  /** Closes, oldest first, one per step up to now. */
  readonly history = input<readonly number[]>([]);
  /** The live price at now; the last close when there's none yet. */
  readonly price = input<number | null>(null);
  /** How many steps right of now can be drawn. */
  readonly futureSteps = input(60);
  /** Labels a step ahead of now for the time axis, e.g. "+2h". */
  readonly stepLabel = input<(step: number) => string>(step => `+${Math.round(step)}`);
  /** The trade read off the line, whose levels are drawn over it. */
  readonly idea = input<TradeIdea | null>(null);
  readonly disabled = input(false);

  /** A new stroke has begun; the old one and its idea are gone. */
  readonly drawStart = output<void>();
  /** The settled line, as prices by step from now (step 0, at the price drawn from). */
  readonly drawn = output<DataPoint[]>();
  /** The drawing was cleared. */
  readonly cleared = output<void>();
  readonly levelChange = output<LevelChange>();

  private readonly zone = inject(NgZone);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly surface = viewChild<ElementRef<HTMLDivElement>>('surface');
  private readonly inkCanvas = viewChild<ElementRef<HTMLCanvasElement>>('ink');

  readonly axis = AXIS;
  readonly ids = (() => {
    const n = ++nextId;
    return {
      history: `draw-history-${n}`, future: `draw-future-${n}`, ink: `draw-ink-${n}`,
      above: `draw-above-${n}`, below: `draw-below-${n}`, glow: `draw-glow-${n}`
    };
  })();

  readonly size = signal({ width: 0, height: 0 });
  readonly drawing = signal(false);
  /** The stroke being drawn, in chart terms, from the anchor at now. */
  private readonly stroke = signal<DataPoint[]>([]);
  /** The settled line, smoothed, in chart terms; null before one is drawn. */
  readonly settled = signal<DataPoint[] | null>(null);
  /** Changes each time a line settles, so its entrance plays again. */
  readonly settleKey = signal([0]);
  /** The pointer over the future, with its price and time. */
  readonly hover = signal<{ x: number; y: number; price: string; time: string } | null>(null);
  /** The range is held still while drawing, so the line doesn't slide under the pen. */
  private readonly frozenRange = signal<{ min: number; max: number } | null>(null);

  private readonly reduceMotion = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
  private observer?: ResizeObserver;
  private frameRequest = 0;
  private lastTick = 0;
  private particles: Particle[] = [];
  private inkAlpha = 0;
  private colors = { gain: '#21d07a', loss: '#ff5c5c' };
  /** The level being dragged by its tag. */
  private dragging: LevelKey | null = null;

  /** The price at now: live, else the last close. */
  readonly nowPrice = computed(() => {
    const live = this.price();
    const history = this.history();
    return live && live > 0 ? live : history[history.length - 1] ?? null;
  });

  private readonly range = computed(() => {
    const frozen = this.frozenRange();
    if (frozen) {
      return frozen;
    }
    const now = this.nowPrice();
    const idea = this.idea();
    const levels = idea ? [idea.entry, idea.target, idea.stop ?? idea.entry] : [];
    return priceRange([...this.history(), ...(now ? [now] : []), ...levels], RANGE_PADDING);
  });

  readonly frame = computed<ChartFrame | null>(() => {
    const { width, height } = this.size();
    if (!width || !height || this.nowPrice() === null) {
      return null;
    }
    const range = this.range();
    return {
      left: 0, right: Math.max(width - AXIS, 1), top: TOP, bottom: Math.max(height - BOTTOM, TOP + 1),
      fromStep: -Math.max(this.history().length, 1), toStep: Math.max(this.futureSteps(), 1),
      minPrice: range.min, maxPrice: range.max
    };
  });

  /** The live dot, in pixels. */
  readonly now = computed<PixelPoint>(() => {
    const frame = this.frame();
    return frame ? toPixel(frame, { step: 0, price: this.nowPrice() ?? 0 }) : { x: 0, y: 0 };
  });

  /** Where today's price sits down the plot, 0-1, splitting the ink between gain and loss. */
  readonly nowOffset = computed(() => {
    const frame = this.frame();
    if (!frame) {
      return 0.5;
    }
    return Math.min(Math.max((this.now().y - frame.top) / (frame.bottom - frame.top || 1), 0), 1);
  });

  /** Changes with the symbol's history, so the history line draws in again. */
  readonly historyKey = computed(() => [`${this.symbol()}:${this.history().length}:${this.history()[0] ?? ''}`]);

  private readonly historyPoints = computed(() => {
    const frame = this.frame();
    if (!frame) {
      return [];
    }
    const history = this.history();
    const points = history.map((price, i) => toPixel(frame, { step: i - history.length, price }));
    return [...points, this.now()];
  });

  readonly historyPath = computed(() => this.linePath(this.historyPoints()));

  readonly historyArea = computed(() => {
    const points = this.historyPoints();
    const frame = this.frame();
    if (points.length < 2 || !frame) {
      return '';
    }
    return `${this.linePath(points)} L${points[points.length - 1].x},${frame.bottom} L${points[0].x},${frame.bottom} Z`;
  });

  /** A sample stroke that traces itself while the future is empty, to show what to do. */
  readonly ghostPath = computed(() => {
    const frame = this.frame();
    if (!frame) {
      return '';
    }
    const now = this.now();
    const span = (frame.right - now.x) * 0.78;
    const lift = (frame.bottom - frame.top) * 0.16;
    const points = [0, 0.18, 0.36, 0.55, 0.75, 1].map((t, i) => ({
      x: now.x + span * t,
      y: now.y - lift * [0, -0.35, 0.25, 0.6, 0.45, 1.15][i]
    }));
    return splinePath(points);
  });

  private readonly settledPixels = computed(() => {
    const frame = this.frame();
    const settled = this.settled();
    return frame && settled ? settled.map(p => toPixel(frame, p)) : [];
  });

  readonly settledPath = computed(() => splinePath(this.settledPixels(), 0.5));

  /** The area between the line and today's price, shaded green above and red below. */
  readonly settledFill = computed(() => {
    const points = this.settledPixels();
    if (points.length < 2) {
      return '';
    }
    const y = this.now().y;
    return `${splinePath(points, 0.5)} L${points[points.length - 1].x},${y} L${points[0].x},${y} Z`;
  });

  readonly priceTicks = computed(() => {
    const frame = this.frame();
    if (!frame) {
      return [];
    }
    return niceTicks(frame.minPrice, frame.maxPrice, 5)
      .map(price => ({ price, y: toPixel(frame, { step: 0, price }).y, label: formatMoney(price) }))
      .filter(tick => tick.y >= frame.top - 1 && tick.y <= frame.bottom + 1);
  });

  readonly timeTicks = computed(() => {
    const frame = this.frame();
    if (!frame) {
      return [];
    }
    const label = this.stepLabel();
    return [0.25, 0.5, 0.75, 1].map(t => {
      const step = Math.round(frame.toStep * t);
      return { step, x: toPixel(frame, { step, price: frame.minPrice }).x, label: label(step) };
    });
  });

  readonly levels = computed<LevelView[]>(() => {
    const frame = this.frame();
    const idea = this.idea();
    if (!frame || !idea || idea.shape === 'flat' || idea.needsShares) {
      return [];
    }
    const y = (price: number) => Math.min(Math.max(toPixel(frame, { step: 0, price }).y, frame.top), frame.bottom);
    const views: LevelView[] = [];
    if (idea.side === 'BUY') {
      views.push({ key: 'target', label: 'Take profit', price: idea.target, y: y(idea.target), tone: 'gain', draggable: true });
      if (idea.entryType === 'LIMIT') {
        views.push({ key: 'entry', label: 'Buy limit', price: idea.entry, y: y(idea.entry), tone: 'entry', draggable: true });
      }
      if (idea.stop !== null) {
        views.push({ key: 'stop', label: 'Stop loss', price: idea.stop, y: y(idea.stop), tone: 'loss', draggable: true });
      }
    } else {
      if (idea.entryType === 'LIMIT') {
        views.push({ key: 'entry', label: 'Sell limit', price: idea.entry, y: y(idea.entry), tone: 'entry', draggable: true });
      }
      views.push({ key: 'target', label: 'Line ends', price: idea.target, y: y(idea.target), tone: 'muted', draggable: false });
    }
    return views;
  });

  /** Dots on the line where the trade gets in and where it takes profit. */
  readonly markers = computed(() => {
    const frame = this.frame();
    const idea = this.idea();
    if (!frame || !idea || idea.shape === 'flat' || idea.needsShares) {
      return [];
    }
    const at = (step: number, price: number) => toPixel(frame, { step, price });
    const entry = at(idea.entryStep, idea.entry);
    const target = at(idea.targetStep, idea.target);
    return [
      { key: 'entry', tone: 'entry', ...entry },
      ...(idea.side === 'BUY' ? [{ key: 'target', tone: 'gain', ...target }] : [])
    ];
  });

  readonly ariaLabel = computed(() =>
    `Chart of ${this.symbol()}. Drag from the live price to the right to draw where you think it goes; Escape clears the drawing.`);

  money = formatMoney;

  constructor() {
    // A line drawn on one stock or horizon means nothing on another: new history starts it over.
    effect(() => {
      this.historyKey();
      untracked(() => {
        this.settled.set(null);
        this.stroke.set([]);
        this.drawing.set(false);
        this.frozenRange.set(null);
        this.inkAlpha = 0;
      });
    });

    // The ink canvas matches the chart's size in device pixels, so the glow stays sharp.
    effect(() => {
      const { width, height } = this.size();
      const canvas = this.inkCanvas()?.nativeElement;
      if (canvas && width && height) {
        const ratio = window.devicePixelRatio || 1;
        canvas.width = Math.round(width * ratio);
        canvas.height = Math.round(height * ratio);
        canvas.getContext('2d')?.setTransform(ratio, 0, 0, ratio, 0, 0);
      }
    });
  }

  ngAfterViewInit(): void {
    const style = getComputedStyle(this.host.nativeElement);
    this.colors = {
      gain: style.getPropertyValue('--gain').trim() || this.colors.gain,
      loss: style.getPropertyValue('--loss').trim() || this.colors.loss
    };
    this.observer = new ResizeObserver(entries => {
      const width = Math.round(entries[0].contentRect.width);
      const height = Math.round(entries[0].contentRect.height);
      const current = this.size();
      if (width !== current.width || height !== current.height) {
        // ResizeObserver callbacks aren't guaranteed to run inside Angular's zone.
        this.zone.run(() => this.size.set({ width, height }));
      }
    });
    this.observer.observe(this.host.nativeElement);
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
    cancelAnimationFrame(this.frameRequest);
  }

  /** Rubs the drawing out, back to an empty future. */
  clear(): void {
    if (this.settled() || this.stroke().length) {
      this.settled.set(null);
      this.stroke.set([]);
      this.inkAlpha = 0;
      this.cleared.emit();
    }
  }

  /** Puts a line on the chart as if drawn - a preset shape, say - and emits it. */
  place(line: readonly DataPoint[]): void {
    if (line.length < 2) {
      return;
    }
    this.drawStart.emit();
    this.settled.set(line.map(p => ({ ...p })));
    this.settleKey.update(([n]) => [n + 1]);
    this.drawn.emit(this.emitted(line));
  }

  down(event: PointerEvent): void {
    const frame = this.frame();
    if (!frame || this.disabled() || event.button !== 0 || this.dragging) {
      return;
    }
    const point = this.local(event);
    // Start at the live dot, or anywhere in the future; the history isn't drawn on.
    if (point.x < this.now().x - 28) {
      return;
    }
    event.preventDefault();
    capture(event, true);
    this.surface()?.nativeElement.focus({ preventScroll: true });
    this.frozenRange.set(this.range());
    this.drawing.set(true);
    this.settled.set(null);
    this.stroke.set([{ step: 0, price: this.nowPrice()! }]);
    this.inkAlpha = 1;
    this.drawStart.emit();
    this.extend(point);
    this.animate();
  }

  move(event: PointerEvent): void {
    const frame = this.frame();
    if (!frame) {
      return;
    }
    const point = this.local(event);
    if (this.drawing()) {
      const samples = event.getCoalescedEvents?.().map(e => this.local(e)) ?? [];
      for (const sample of samples.length ? samples : [point]) {
        this.extend(sample);
      }
    }
    this.track(point);
  }

  up(event: PointerEvent): void {
    if (!this.drawing()) {
      return;
    }
    capture(event, false);
    this.drawing.set(false);
    // The crosshair followed the pen; it comes back on the next move, off the finished line.
    this.hover.set(null);
    const frame = this.frame();
    const stroke = this.stroke();
    const pixels = frame ? stroke.map(p => toPixel(frame, p)) : [];
    const across = pixels.length ? pixels[pixels.length - 1].x - pixels[0].x : 0;
    if (!frame || pixels.length < 3 || across < MIN_STROKE) {
      // A tap: nothing drawn.
      this.stroke.set([]);
      this.frozenRange.set(null);
      this.inkAlpha = 0;
      this.cleared.emit();
      return;
    }
    const line = smooth(thin(pixels, 5), 3).map(p => toData(frame, p));
    line[0] = { step: 0, price: stroke[0].price };
    this.settled.set(line);
    this.settleKey.update(([n]) => [n + 1]);
    this.stroke.set([]);
    this.frozenRange.set(null);
    this.animate();
    this.drawn.emit(this.emitted(line));
  }

  grab(event: PointerEvent, level: LevelView): void {
    event.stopPropagation();
    if (!level.draggable || event.button !== 0) {
      return;
    }
    event.preventDefault();
    capture(event, true);
    this.dragging = level.key;
    this.frozenRange.set(this.range());
  }

  drag(event: PointerEvent, level: LevelView): void {
    const frame = this.frame();
    if (this.dragging !== level.key || !frame) {
      return;
    }
    event.stopPropagation();
    const y = Math.min(Math.max(this.local(event).y, frame.top), frame.bottom);
    this.levelChange.emit({ level: level.key, price: cents(toData(frame, { x: 0, y }).price) });
  }

  release(event: PointerEvent): void {
    if (this.dragging) {
      event.stopPropagation();
      capture(event, false);
      this.dragging = null;
      this.frozenRange.set(null);
    }
  }

  /** Arrow keys move a level by half a percent of the chart's range; Shift moves it five times as far. */
  nudge(event: KeyboardEvent, level: LevelView): void {
    const frame = this.frame();
    if (!level.draggable || !frame) {
      return;
    }
    const step = (frame.maxPrice - frame.minPrice) * 0.005 * (event.shiftKey ? 5 : 1);
    const delta = { ArrowUp: step, ArrowRight: step, ArrowDown: -step, ArrowLeft: -step }[event.key];
    if (delta === undefined) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    this.levelChange.emit({ level: level.key, price: cents(Math.max(level.price + delta, 0.01)) });
  }

  /** The line as emitted: prices at even steps, a few per candle at most. */
  private emitted(line: readonly DataPoint[]): DataPoint[] {
    return resample(line, Math.max(this.futureSteps() / 120, 0.1));
  }

  private extend(point: PixelPoint): void {
    const frame = this.frame()!;
    const clamped = {
      x: Math.min(point.x, frame.right),
      y: Math.min(Math.max(point.y, frame.top), frame.bottom)
    };
    const anchor = this.now();
    const pixels = this.stroke().map(p => toPixel(frame, p));
    const before = pixels[pixels.length - 1];
    const next = extendStroke(pixels, clamped, anchor);
    this.stroke.set(next.map((p, i) => (i === 0 ? this.stroke()[0] : toData(frame, p))));
    const tip = next[next.length - 1];
    if (!this.reduceMotion && before && Math.hypot(tip.x - before.x, tip.y - before.y) > 1.5) {
      this.spark(tip);
    }
  }

  private track(point: PixelPoint): void {
    const frame = this.frame()!;
    const inFuture = point.x >= this.now().x && point.x <= frame.right && point.y >= frame.top && point.y <= frame.bottom;
    if (!inFuture && !this.drawing()) {
      this.hover.set(null);
      return;
    }
    const x = Math.min(Math.max(point.x, this.now().x), frame.right);
    const y = Math.min(Math.max(point.y, frame.top), frame.bottom);
    const data = toData(frame, { x, y });
    this.hover.set({ x, y, price: formatMoney(data.price), time: this.stepLabel()(data.step) });
  }

  private local(event: { clientX: number; clientY: number }): PixelPoint {
    const rect = this.host.nativeElement.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  }

  private linePath(points: readonly PixelPoint[]): string {
    return points.map((p, i) => `${i ? 'L' : 'M'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join('');
  }

  private spark(at: PixelPoint): void {
    const color = at.y <= this.now().y ? this.colors.gain : this.colors.loss;
    for (let i = 0; i < 2; i++) {
      const angle = Math.random() * Math.PI * 2;
      const speed = 0.3 + Math.random() * 1.1;
      const max = 450 + Math.random() * 450;
      this.particles.push({
        x: at.x, y: at.y, vx: Math.cos(angle) * speed, vy: Math.sin(angle) * speed - 0.4,
        life: max, max, size: 0.8 + Math.random() * 1.8, color
      });
    }
    if (this.particles.length > 220) {
      this.particles.splice(0, this.particles.length - 220);
    }
  }

  /** Runs the ink and sparks on animation frames, outside Angular, until there's nothing left to draw. */
  private animate(): void {
    if (this.frameRequest) {
      return;
    }
    this.zone.runOutsideAngular(() => {
      this.lastTick = performance.now();
      const tick = (time: number) => {
        const dt = Math.min(time - this.lastTick, 50);
        this.lastTick = time;
        if (!this.drawing()) {
          this.inkAlpha = Math.max(this.inkAlpha - dt / 320, 0);
        }
        this.paint(dt);
        this.frameRequest = this.drawing() || this.inkAlpha > 0 || this.particles.length
          ? requestAnimationFrame(tick)
          : 0;
      };
      this.frameRequest = requestAnimationFrame(tick);
    });
  }

  private paint(dt: number): void {
    const canvas = this.inkCanvas()?.nativeElement;
    const ctx = canvas?.getContext('2d');
    const frame = this.frame();
    if (!canvas || !ctx || !frame) {
      return;
    }
    const { width, height } = this.size();
    ctx.clearRect(0, 0, width, height);

    const points = this.stroke().map(p => toPixel(frame, p));
    if (points.length > 1 && this.inkAlpha > 0) {
      const split = this.nowOffset();
      const gradient = ctx.createLinearGradient(0, frame.top, 0, frame.bottom);
      gradient.addColorStop(0, this.colors.gain);
      gradient.addColorStop(split, this.colors.gain);
      gradient.addColorStop(split, this.colors.loss);
      gradient.addColorStop(1, this.colors.loss);
      ctx.save();
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.strokeStyle = gradient;
      const trace = () => {
        ctx.beginPath();
        ctx.moveTo(points[0].x, points[0].y);
        for (let i = 1; i < points.length - 1; i++) {
          const mid = { x: (points[i].x + points[i + 1].x) / 2, y: (points[i].y + points[i + 1].y) / 2 };
          ctx.quadraticCurveTo(points[i].x, points[i].y, mid.x, mid.y);
        }
        ctx.lineTo(points[points.length - 1].x, points[points.length - 1].y);
        ctx.stroke();
      };
      // A wide soft glow under a bright core.
      ctx.globalAlpha = 0.35 * this.inkAlpha;
      ctx.lineWidth = 10;
      ctx.shadowBlur = 24;
      ctx.shadowColor = points[points.length - 1].y <= this.now().y ? this.colors.gain : this.colors.loss;
      trace();
      ctx.globalAlpha = this.inkAlpha;
      ctx.lineWidth = 3;
      ctx.shadowBlur = 8;
      trace();
      if (this.drawing()) {
        const tip = points[points.length - 1];
        ctx.beginPath();
        ctx.fillStyle = '#fff';
        ctx.shadowBlur = 18;
        ctx.arc(tip.x, tip.y, 4, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.restore();
    }

    if (this.particles.length) {
      ctx.save();
      ctx.globalCompositeOperation = 'lighter';
      const step = dt / 16;
      this.particles = this.particles.filter(p => (p.life -= dt) > 0);
      for (const p of this.particles) {
        p.x += p.vx * step;
        p.y += p.vy * step;
        p.vy += 0.015 * step;
        p.vx *= 0.985;
        ctx.globalAlpha = Math.max(p.life / p.max, 0) * 0.9;
        ctx.fillStyle = p.color;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.size * (0.4 + 0.6 * (p.life / p.max)), 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.restore();
    }
  }
}
