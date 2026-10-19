import {
  Component, DestroyRef, ElementRef, NgZone, ViewChild, computed, effect, inject, input, output, signal,
  untracked
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { formatMoney } from '../../shared/format';
import { RollingNumberComponent } from '../../shared/rolling-number';
import {
  Allocation, DonutSlice, OTHER_THRESHOLD, SliceArc, donutLabels, formatShare, sliceArcs, spreadLabels
} from './allocation';
import { TargetAllocation } from './target-allocation';
import { SliceTrade } from './trade-preview';
import { FINE_STEP, STEP, WHOLE, boundaryPosition, boundarySides, formatTarget, onTarget, targetChange } from './targets';

/** A label beside the ring, placed in pixels, with the leader line back to its arc. */
interface PlacedLabel {
  key: string;
  keys: string[];
  text: string;
  percent: string;
  /** The keys' combined target, when it differs from what they hold now. */
  target: string | null;
  /** Names a stock added to the donut, not owned yet. */
  added: boolean;
  /** The trade a lone slice needs: "↑6" or "↓4". */
  badge: { side: 'BUY' | 'SELL'; text: string } | null;
  x: number;
  y: number;
  anchor: 'start' | 'end';
  leader: string;
}

/** One slice as drawn: its path, and how far it moves out while it's the active one. */
interface DrawnSlice {
  slice: DonutSlice;
  path: string;
  /** Offset along the slice's middle, for the active slice's pop-out. */
  dx: number;
  dy: number;
  ariaLabel: string;
  /** Targeted at 0%: drawn as a dashed outline, to be sold off. */
  sold: boolean;
}

/** One slice of the target ring. */
interface TargetArc {
  key: string;
  color: string;
  path: string;
  /** A stock added to the donut, not owned yet. */
  added: boolean;
}

/** A draggable boundary between two targets, on the target ring. */
interface TargetHandle {
  boundary: number;
  x: number;
  y: number;
  /** The tick across the ring. */
  tick: string;
  label: string;
  valueNow: number;
  valueMax: number;
  valueText: string;
  movable: boolean;
}

/** A drag under way: the pointer, and the boundary it moves once the first move picks one. */
interface DragSession {
  pointerId: number;
  /** Boundaries under the pointer at the start; several when a 0% slice sits between them. */
  candidates: number[];
  boundary: number | null;
  moved: boolean;
}

/** Room each side of the ring for a leader line and a label; phones get a slimmer gutter. */
const LABEL_GUTTER = 92;
const NARROW_GUTTER = 68;
const NARROW = 440;
/** Vertical room one two-line label needs. */
const LABEL_GAP = 34;
const INNER_RATIO = 0.64;
/** A small ring keeps a bigger hole, so the total still fits inside it. */
const NARROW_INNER_RATIO = 0.7;
const POP = 6;
/** The target ring: a gap outside the current ring, then the ring itself. */
const RING_GAP = 7;
const RING_WIDTH = 12;
/** With a target ring, the active slice moves out less so it stays clear of it. */
const POP_WITH_TARGETS = 3;
const TWEEN_MS = 520;
const TOOLTIP_WIDTH = 196;
const TOOLTIP_HEIGHT = 112;

const shares = new Intl.NumberFormat('en-US', { maximumFractionDigits: 4 });
const easeOut = (t: number) => 1 - (1 - t) ** 3;

/**
 * Rebalance Donut: an account's holdings and cash as an interactive donut. Each slice is sized by
 * its live market value, labelled beside the ring (small slices share an 'Other' label), and
 * shows its details on hover or focus. The account's total sits in the middle and ticks with
 * prices. Drawn as SVG in real pixels, sized by a ResizeObserver like the line chart, so labels
 * keep their size on any screen. Slices sweep in on first draw and ease between live sizes,
 * except for users who prefer reduced motion.
 *
 * Keyboard: the ring is one tab stop. Arrow keys move between slices, Enter or Space pins one,
 * Escape lets it go.
 *
 * Given a `targets` store, the donut also draws the target allocation as an outer ring over the
 * current one, with a handle on each boundary between targets. Handles drag with mouse, touch or
 * pen (pointer events, captured, with touch-action none so the page doesn't scroll), snapping to
 * whole percentages or 0.1% with Shift. Each handle is a slider for the keyboard too: arrow keys
 * move it 1%, Shift+arrow 0.1%, like the order chart's price handles. A slice targeted at 0% is
 * drawn as a dashed outline, and dragging from it pulls it back out.
 */
@Component({
  selector: 'app-allocation-donut',
  imports: [CommonModule, RollingNumberComponent],
  templateUrl: './allocation-donut.html',
  styleUrl: './allocation-donut.css'
})
export class AllocationDonutComponent {
  readonly allocation = input<Allocation | null>(null);
  readonly state = input<'loading' | 'error' | 'ready'>('ready');
  readonly errorMessage = input<string | null>(null);
  /** The target allocation to draw and edit; without one the donut only shows the current split. */
  readonly targets = input<TargetAllocation | null>(null);
  /** The trades the targets take, by symbol, for each slice's badge; null to show none. */
  readonly trades = input<Record<string, SliceTrade> | null>(null);
  /** False to put the page's own content (marked donutAside) beside the ring instead of the legend. */
  readonly showLegend = input(true);

  readonly retry = output<void>();
  readonly addHolding = output<void>();
  /** The slice the client pinned by clicking it or pressing Enter, or null. */
  readonly selectedChange = output<string | null>();

  private chartRef?: ElementRef<HTMLElement>;
  @ViewChild('svg') private svgRef?: ElementRef<SVGSVGElement>;
  private session: DragSession | null = null;
  /** Set as a drag ends, so the click that follows doesn't also pin a slice. */
  private justDragged = false;
  /** The boundary being dragged, for styling. */
  readonly dragging = signal<number | null>(null);

  private readonly zone = inject(NgZone);
  private readonly destroyRef = inject(DestroyRef);
  private observer?: ResizeObserver;
  private frame = 0;
  private readonly reducedMotion =
    typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

  readonly width = signal(0);
  readonly height = signal(0);

  readonly hovered = signal<string | null>(null);
  readonly focused = signal<string | null>(null);
  readonly selected = signal<string | null>(null);
  /** Whether focus arrived from the keyboard, so the focused slice gets a visible outline. */
  readonly keyboardFocus = signal(false);
  /** The slice whose details are showing: pointed at, else focused, else pinned. */
  readonly active = computed(() => this.hovered() ?? this.focused() ?? this.selected());

  readonly slices = computed(() => this.allocation()?.slices ?? []);
  readonly total = computed(() => this.allocation()?.total ?? 0);
  readonly holdings = computed(() => this.slices().filter(s => s.kind === 'holding'));
  readonly cash = computed(() => this.slices().find(s => s.kind === 'cash') ?? null);
  readonly unpriced = computed(() => this.allocation()?.unpriced ?? []);
  readonly isEmpty = computed(() => this.holdings().length === 0);
  readonly grouped = computed(() => this.holdings().filter(s => s.grouped));
  readonly groupedShare = computed(() => this.grouped().reduce((sum, s) => sum + s.share, 0));
  readonly otherThreshold = formatShare(OTHER_THRESHOLD);

  /** The arcs drawn right now: the live arcs, or a frame on the way to them. */
  readonly arcs = signal<SliceArc[]>([]);
  private readonly liveArcs = computed(() => sliceArcs(this.slices()));

  readonly geometry = computed(() => {
    const w = this.width();
    const h = this.height();
    const narrow = w < NARROW;
    const ring = this.targets() ? RING_GAP + RING_WIDTH : 0;
    const outer = Math.max(44, Math.min(h / 2 - 14 - ring, w / 2 - (narrow ? NARROW_GUTTER : LABEL_GUTTER) - ring));
    const inner = outer * (narrow ? NARROW_INNER_RATIO : INNER_RATIO);
    // `edge` is the outside of everything drawn: labels and the details card sit beyond it.
    // Below `roomy` the hole only has room for the figure itself.
    return { w, h, cx: w / 2, cy: h / 2, outer, inner, edge: outer + ring, roomy: inner >= 72 };
  });

  readonly drawn = computed<DrawnSlice[]>(() => {
    const { cx, cy, outer, inner, w } = this.geometry();
    const arcs = this.arcs();
    if (!w) {
      return [];
    }
    const plan = this.targets();
    const pop = plan ? POP_WITH_TARGETS : POP;
    const byKey = new Map(arcs.map(a => [a.key, a]));
    return this.slices().flatMap(slice => {
      const arc = byKey.get(slice.key);
      if (!arc) {
        return [];
      }
      const middle = (arc.start + arc.end) / 2;
      return [{
        slice,
        path: annulus(cx, cy, outer, inner, arc.start, arc.end),
        dx: Math.sin(middle) * pop,
        dy: -Math.cos(middle) * pop,
        ariaLabel: this.describe(slice),
        sold: !!plan && plan.targetOf(slice.key) === 0 && slice.share > 0
      }];
    });
  });

  /** The target ring's slices, in the same order and colours as the current ring. */
  readonly targetArcs = computed<TargetArc[]>(() => {
    const plan = this.targets();
    const { cx, cy, outer, w } = this.geometry();
    if (!plan || !w) {
      return [];
    }
    const colors = new Map(this.slices().map(s => [s.key, s.color]));
    const added = new Set(this.slices().filter(s => s.added).map(s => s.key));
    const targets = plan.targets();
    let start = 0;
    return plan.keys().flatMap((key, i) => {
      const from = start;
      start += targets[i];
      if (targets[i] <= 0) {
        return [];
      }
      return [{
        key,
        color: colors.get(key) ?? 'var(--muted-foreground)',
        added: added.has(key),
        path: annulus(cx, cy, outer + RING_GAP + RING_WIDTH, outer + RING_GAP, turn(from), turn(start))
      }];
    });
  });

  /** A handle on every boundary between targets; the seam at 12 o'clock stays put. */
  readonly handles = computed<TargetHandle[]>(() => {
    const plan = this.targets();
    const { cx, cy, outer, w } = this.geometry();
    if (!plan || !w) {
      return [];
    }
    const targets = plan.targets();
    const keys = plan.keys();
    const locked = plan.lockedFlags();
    const middle = outer + RING_GAP + RING_WIDTH / 2;
    return keys.slice(0, -1).map((key, boundary) => {
      const angle = turn(boundaryPosition(targets, boundary));
      const sides = boundarySides(locked, boundary);
      const at = (radius: number) => `${r(cx + Math.sin(angle) * radius)},${r(cy - Math.cos(angle) * radius)}`;
      const before = sides?.before ?? boundary;
      const after = sides?.after ?? boundary + 1;
      return {
        boundary,
        x: r(cx + Math.sin(angle) * middle),
        y: r(cy - Math.cos(angle) * middle),
        tick: `M${at(outer + RING_GAP - 2)} L${at(outer + RING_GAP + RING_WIDTH + 2)}`,
        label: `Target between ${plan.label(key)} and ${plan.label(keys[boundary + 1])}`,
        valueNow: targets[before] / 10,
        valueMax: (targets[before] + targets[after]) / 10,
        valueText: plan.boundaryText(boundary),
        movable: !!sides
      };
    });
  });

  readonly labels = computed<PlacedLabel[]>(() => {
    const { cx, cy, edge: outer, w, h } = this.geometry();
    const plan = this.targets();
    const slices = this.slices();
    const arcs = this.arcs();
    if (!w || arcs.length !== slices.length) {
      return [];
    }
    const raw = donutLabels(slices, arcs).map(label => ({
      ...label,
      // A lone slice is the whole ring; its label sits level with the middle on the right.
      angle: slices.length === 1 ? Math.PI / 2 : label.angle
    }));
    const placed: PlacedLabel[] = [];
    for (const side of ['right', 'left'] as const) {
      const onSide = raw.filter(l => (Math.sin(l.angle) >= 0) === (side === 'right'));
      const ys = spreadLabels(onSide.map(l => cy - Math.cos(l.angle) * (outer + 14)), LABEL_GAP, 20, h - 22);
      onSide.forEach((label, i) => {
        const sign = side === 'right' ? 1 : -1;
        const edge = { x: cx + Math.sin(label.angle) * (outer + 3), y: cy - Math.cos(label.angle) * (outer + 3) };
        const elbow = { x: cx + Math.sin(label.angle) * (outer + 14), y: cy - Math.cos(label.angle) * (outer + 14) };
        const x = cx + sign * (outer + (w < NARROW ? 22 : 30));
        const y = ys[i];
        placed.push({
          key: label.key,
          keys: label.keys,
          text: label.text,
          percent: formatShare(label.share),
          // Phones keep labels short; the targets table has every target.
          target: plan && w >= NARROW ? this.changedTarget(plan, label.keys, label.share) : null,
          badge: w >= NARROW ? this.badgeFor(label.keys) : null,
          added: label.keys.length === 1 && !!slices.find(s => s.key === label.keys[0])?.added,
          x,
          y,
          anchor: side === 'right' ? 'start' : 'end',
          leader: `M${r(edge.x)},${r(edge.y)} L${r(elbow.x)},${r(elbow.y)} L${r(x - sign * 5)},${r(y - 4)}`
        });
      });
    }
    return placed;
  });

  readonly activeSlice = computed(() => this.slices().find(s => s.key === this.active()) ?? null);

  /** Where the details card sits: just outside the active slice, kept inside the chart. */
  readonly tooltip = computed(() => {
    const slice = this.activeSlice();
    const arc = this.arcs().find(a => a.key === slice?.key);
    const { cx, cy, edge: outer, w, h } = this.geometry();
    if (!slice || !arc || !w || this.dragging() !== null) {
      return null;
    }
    const middle = this.slices().length === 1 ? Math.PI / 2 : (arc.start + arc.end) / 2;
    // Just past the ring's edge, so the card never hides the slice it describes.
    const px = cx + Math.sin(middle) * (outer + 8);
    const py = cy - Math.cos(middle) * (outer + 8);
    const right = Math.sin(middle) >= 0;
    const left = right ? px + 8 : px - 8 - TOOLTIP_WIDTH;
    const plan = this.targets();
    const target = plan?.targetOf(slice.key) ?? null;
    const trades = this.trades();
    const height = TOOLTIP_HEIGHT + (target !== null ? 44 : 0);
    return {
      slice,
      target: target !== null ? formatTarget(target) : null,
      /** Dollars to buy (positive) or sell to reach the target. */
      change: target !== null ? targetChange(target, slice.share, this.total()) : null,
      /** The planned trade (null for none), or undefined when trades aren't shown. Cash isn't traded. */
      trade: trades && slice.kind === 'holding' ? trades[slice.key] ?? null : undefined,
      left: clamp(left, 4, w - TOOLTIP_WIDTH - 4),
      top: clamp(py - height / 2, 4, h - height - 4)
    };
  });

  /** The keyboard's way into the ring: the active slice, else the first. */
  readonly tabStop = computed(() => this.active() ?? this.slices()[0]?.key ?? null);

  readonly summary = computed(() => {
    const count = this.holdings().length;
    const what = count ? `${count} holding${count === 1 ? '' : 's'}${this.cash() ? ' and cash' : ''}` : 'cash only';
    return `Allocation of ${formatMoney(this.total())}: ${what}.`;
  });

  readonly centreCaption = computed(() => {
    const count = this.holdings().length;
    return count ? `${count} holding${count === 1 ? '' : 's'}${this.cash() ? ' + cash' : ''}` : 'All in cash';
  });

  money = formatMoney;
  share = formatShare;
  abs = Math.abs;
  formatShares = (value: number | null) => (value == null ? '—' : shares.format(value));

  constructor() {
    // Ease from what's drawn to the live arcs whenever slices change size, appear or go.
    effect(() => {
      const to = this.liveArcs();
      untracked(() => this.animateTo(to));
    });

    // A pinned slice that's gone (sold, or the account changed) is let go.
    effect(() => {
      const keys = this.slices().map(s => s.key);
      untracked(() => {
        for (const which of [this.selected, this.hovered, this.focused]) {
          if (which() && !keys.includes(which()!)) {
            which.set(null);
          }
        }
      });
    });

    this.destroyRef.onDestroy(() => {
      cancelAnimationFrame(this.frame);
      this.observer?.disconnect();
    });
  }

  /** The chart appears once loaded, so the observer attaches when it does. */
  observeChart(): void {
    const element = this.chartRef?.nativeElement;
    if (!element || this.observer) {
      return;
    }
    this.observer = new ResizeObserver(entries => {
      const { width, height } = entries[0].contentRect;
      const w = Math.round(width);
      const h = Math.round(height);
      if (w !== this.width() || h !== this.height()) {
        // ResizeObserver callbacks aren't guaranteed to run inside Angular's zone.
        this.zone.run(() => {
          this.width.set(w);
          this.height.set(h);
        });
      }
    });
    this.observer.observe(element);
  }

  @ViewChild('chart')
  private set chart(element: ElementRef<HTMLElement> | undefined) {
    this.chartRef = element;
    if (!element) {
      this.observer?.disconnect();
      this.observer = undefined;
      this.width.set(0);
    } else {
      queueMicrotask(() => this.observeChart());
    }
  }

  isDimmed(key: string): boolean {
    const active = this.active();
    return active !== null && active !== key;
  }

  labelActive(label: PlacedLabel): boolean {
    const active = this.active();
    return active !== null && label.keys.includes(active);
  }

  hover(key: string | null): void {
    if (this.session?.moved) {
      return;
    }
    this.hovered.set(key);
  }

  toggle(key: string): void {
    if (this.justDragged) {
      return;
    }
    const next = this.selected() === key ? null : key;
    this.selected.set(next);
    this.selectedChange.emit(next);
  }

  onFocus(key: string, event: FocusEvent): void {
    const target = event.target as Element;
    this.keyboardFocus.set(typeof target.matches === 'function' && target.matches(':focus-visible'));
    this.focused.set(key);
  }

  onBlur(): void {
    this.focused.set(null);
    this.keyboardFocus.set(false);
  }

  onKey(event: KeyboardEvent, key: string): void {
    const keys = this.slices().map(s => s.key);
    const index = keys.indexOf(key);
    let next: string | undefined;
    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowDown': next = keys[(index + 1) % keys.length]; break;
      case 'ArrowLeft':
      case 'ArrowUp': next = keys[(index - 1 + keys.length) % keys.length]; break;
      case 'Home': next = keys[0]; break;
      case 'End': next = keys[keys.length - 1]; break;
      case 'Enter':
      case ' ':
        event.preventDefault();
        this.toggle(key);
        return;
      case 'Escape':
        if (this.selected()) {
          event.preventDefault();
          this.selected.set(null);
          this.selectedChange.emit(null);
        }
        return;
      default: return;
    }
    event.preventDefault();
    this.hovered.set(null);
    const svg = (event.currentTarget as SVGElement).ownerSVGElement;
    svg?.querySelector<SVGElement>(`[data-key="${CSS.escape(next)}"]`)?.focus();
  }

  /** A handle pressed: starts a drag on its boundary, or on whichever coincident one the first move picks. */
  startHandleDrag(event: PointerEvent, boundary: number): void {
    const targets = this.targets()?.targets() ?? [];
    const at = boundaryPosition(targets, boundary);
    const candidates = this.handles().filter(h => boundaryPosition(targets, h.boundary) === at).map(h => h.boundary);
    this.startDrag(event, candidates);
  }

  /** A 0% slice pressed: pulls it back out from whichever side the pointer moves to. */
  startSoldDrag(event: PointerEvent, key: string): void {
    const plan = this.targets();
    const index = plan?.keys().indexOf(key) ?? -1;
    if (!plan || index < 0) {
      return;
    }
    const candidates = [index - 1, index].filter(b => b >= 0 && b < plan.keys().length - 1);
    this.startDrag(event, candidates);
  }

  onDragMove(event: PointerEvent): void {
    const session = this.session;
    const plan = this.targets();
    if (!session || !plan || event.pointerId !== session.pointerId) {
      return;
    }
    const targets = plan.targets();
    const reference = boundaryPosition(targets, session.boundary ?? session.candidates[0]);
    // The nearest way round to the pointer, so crossing 12 o'clock doesn't jump a whole turn.
    const raw = this.positionAt(event);
    const position = [raw - WHOLE, raw, raw + WHOLE].reduce((a, b) => (Math.abs(b - reference) < Math.abs(a - reference) ? b : a));
    if (session.boundary === null) {
      if (Math.abs(position - reference) < 2) {
        return;
      }
      // Clockwise moves the last of the coincident boundaries, so the 0% slice before it grows.
      session.boundary = position > reference ? Math.max(...session.candidates) : Math.min(...session.candidates);
    }
    session.moved = true;
    this.hovered.set(null);
    this.dragging.set(session.boundary);
    plan.dragTo(session.boundary, position, event.shiftKey ? FINE_STEP : STEP);
  }

  endDrag(event: PointerEvent): void {
    const session = this.session;
    if (!session || event.pointerId !== session.pointerId) {
      return;
    }
    try {
      this.svgRef?.nativeElement.releasePointerCapture?.(event.pointerId);
    } catch {
      // Nothing was captured.
    }
    this.session = null;
    this.dragging.set(null);
    if (session.moved && session.boundary !== null) {
      this.targets()?.finishDrag(session.boundary);
      this.justDragged = true;
      setTimeout(() => { this.justDragged = false; });
    }
  }

  /** Arrow keys move a boundary 1% (Shift: 0.1%); up and right are clockwise, as on the order chart. */
  onHandleKey(event: KeyboardEvent, boundary: number): void {
    const step = event.shiftKey ? FINE_STEP : STEP;
    let delta = 0;
    switch (event.key) {
      case 'ArrowUp':
      case 'ArrowRight': delta = step; break;
      case 'ArrowDown':
      case 'ArrowLeft': delta = -step; break;
      default: return;
    }
    event.preventDefault();
    this.targets()?.nudge(boundary, delta);
  }

  /** "↑ Buy 6", "↓ Sell 4", or "None" for a slice with no trade. */
  tradeText(trade: SliceTrade | null | undefined): string {
    if (!trade) {
      return 'None';
    }
    return `${trade.side === 'BUY' ? '↑ Buy' : '↓ Sell'} ${trade.quantity}`;
  }

  isLocked(key: string): boolean {
    return this.targets()?.isLocked(key) ?? false;
  }

  trackHandle = (_: number, handle: TargetHandle) => handle.boundary;
  trackTarget = (_: number, arc: TargetArc) => arc.key;
  trackSlice = (_: number, drawn: DrawnSlice) => drawn.slice.key;
  trackLabel = (_: number, label: PlacedLabel) => label.key;
  trackKey = (_: number, slice: DonutSlice) => slice.key;

  private describe(slice: DonutSlice): string {
    const parts = [slice.kind === 'cash' ? 'Cash' : `${slice.label}, ${slice.name}`, formatMoney(slice.value)];
    if (slice.shares != null) {
      parts.push(`${shares.format(slice.shares)} share${slice.shares === 1 ? '' : 's'}`);
    }
    parts.push(`${formatShare(slice.share)} of account`);
    const target = this.targets()?.targetOf(slice.key);
    if (target != null) {
      parts.push(`target ${formatTarget(target)}`);
    }
    return parts.join(', ');
  }

  /** A lone slice's trade as a badge; the 'Other' label leaves its trades to the table. */
  private badgeFor(keys: string[]): PlacedLabel['badge'] {
    const trade = keys.length === 1 ? this.trades()?.[keys[0]] : undefined;
    return trade ? { side: trade.side, text: `${trade.side === 'BUY' ? '↑' : '↓'}${trade.quantity}` } : null;
  }

  /** The keys' combined target, or null when it reads the same as what they hold now. */
  private changedTarget(plan: TargetAllocation, keys: string[], share: number): string | null {
    const target = keys.reduce((sum, key) => sum + (plan.targetOf(key) ?? 0), 0);
    return onTarget(target, share) || formatTarget(target) === formatShare(share) ? null : formatTarget(target);
  }

  private startDrag(event: PointerEvent, candidates: number[]): void {
    if ((event.button !== 0 && event.pointerType === 'mouse') || candidates.length === 0) {
      return;
    }
    try {
      // Keeps the drag going when the pointer leaves the handle, or the chart.
      this.svgRef?.nativeElement.setPointerCapture?.(event.pointerId);
    } catch {
      // No active pointer to capture (e.g. a synthetic event); moves over the chart still drag.
    }
    this.session = { pointerId: event.pointerId, candidates, boundary: candidates.length === 1 ? candidates[0] : null, moved: false };
    event.preventDefault();
  }

  /** The pointer's angle around the centre, in tenths of a percent clockwise from 12 o'clock. */
  private positionAt(event: PointerEvent): number {
    const svg = this.svgRef?.nativeElement;
    const { cx, cy } = this.geometry();
    if (!svg) {
      return 0;
    }
    const rect = svg.getBoundingClientRect();
    const angle = Math.atan2(event.clientX - rect.left - cx, -(event.clientY - rect.top - cy));
    return ((angle < 0 ? angle + Math.PI * 2 : angle) / (Math.PI * 2)) * WHOLE;
  }

  private animateTo(to: SliceArc[]): void {
    cancelAnimationFrame(this.frame);
    const from = this.arcs();
    if (this.reducedMotion || typeof requestAnimationFrame !== 'function') {
      this.arcs.set(to);
      return;
    }
    // First draw sweeps clockwise from 12 o'clock; after that each arc eases from where it was,
    // and a new slice grows out of its own start.
    const sweep = from.length === 0;
    const previous = new Map(from.map(a => [a.key, a]));
    const startOf = (arc: SliceArc) => previous.get(arc.key) ?? { key: arc.key, start: arc.start, end: arc.start };
    if (!sweep && to.every(a => {
      const p = previous.get(a.key);
      return p && Math.abs(p.start - a.start) < 1e-4 && Math.abs(p.end - a.end) < 1e-4;
    }) && to.length === from.length) {
      this.arcs.set(to);
      return;
    }
    const began = performance.now();
    const step = (now: number) => {
      const t = easeOut(Math.min(1, (now - began) / TWEEN_MS));
      this.arcs.set(to.map(arc => {
        if (sweep) {
          return { key: arc.key, start: arc.start * t, end: arc.end * t };
        }
        const was = startOf(arc);
        return { key: arc.key, start: was.start + (arc.start - was.start) * t, end: was.end + (arc.end - was.end) * t };
      }));
      if (t < 1) {
        this.frame = requestAnimationFrame(step);
      }
    };
    this.frame = requestAnimationFrame(step);
  }
}

const r = (value: number) => Math.round(value * 100) / 100;

/** Tenths of a percent as an angle: radians clockwise from 12 o'clock. */
const turn = (tenths: number) => (tenths / WHOLE) * Math.PI * 2;
const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), Math.max(min, max));

/** A ring segment between two angles (radians, clockwise from 12 o'clock); a full turn is a whole ring. */
function annulus(cx: number, cy: number, outer: number, inner: number, start: number, end: number): string {
  const span = end - start;
  if (span <= 0) {
    return '';
  }
  const point = (radius: number, angle: number) => `${r(cx + Math.sin(angle) * radius)},${r(cy - Math.cos(angle) * radius)}`;
  if (span >= Math.PI * 2 - 1e-6) {
    return `M${point(outer, 0)} A${outer},${outer} 0 1 1 ${point(outer, Math.PI)} A${outer},${outer} 0 1 1 ${point(outer, 0)} Z `
      + `M${point(inner, 0)} A${inner},${inner} 0 1 0 ${point(inner, Math.PI)} A${inner},${inner} 0 1 0 ${point(inner, 0)} Z`;
  }
  const large = span > Math.PI ? 1 : 0;
  return `M${point(outer, start)} A${r(outer)},${r(outer)} 0 ${large} 1 ${point(outer, end)} `
    + `L${point(inner, end)} A${r(inner)},${r(inner)} 0 ${large} 0 ${point(inner, start)} Z`;
}
