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

/** A label beside the ring, placed in pixels, with the leader line back to its arc. */
interface PlacedLabel {
  key: string;
  keys: string[];
  text: string;
  percent: string;
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

  readonly retry = output<void>();
  readonly addHolding = output<void>();
  /** The slice the client pinned by clicking it or pressing Enter, or null. */
  readonly selectedChange = output<string | null>();

  private chartRef?: ElementRef<HTMLElement>;

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
  private readonly targetArcs = computed(() => sliceArcs(this.slices()));

  readonly geometry = computed(() => {
    const w = this.width();
    const h = this.height();
    const narrow = w < NARROW;
    const outer = Math.max(44, Math.min(h / 2 - 14, w / 2 - (narrow ? NARROW_GUTTER : LABEL_GUTTER)));
    const inner = outer * (narrow ? NARROW_INNER_RATIO : INNER_RATIO);
    // Below this the hole only has room for the figure itself.
    return { w, h, cx: w / 2, cy: h / 2, outer, inner, roomy: inner >= 72 };
  });

  readonly drawn = computed<DrawnSlice[]>(() => {
    const { cx, cy, outer, inner, w } = this.geometry();
    const arcs = this.arcs();
    if (!w) {
      return [];
    }
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
        dx: Math.sin(middle) * POP,
        dy: -Math.cos(middle) * POP,
        ariaLabel: this.describe(slice)
      }];
    });
  });

  readonly labels = computed<PlacedLabel[]>(() => {
    const { cx, cy, outer, w, h } = this.geometry();
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
    const { cx, cy, outer, w, h } = this.geometry();
    if (!slice || !arc || !w) {
      return null;
    }
    const middle = this.slices().length === 1 ? Math.PI / 2 : (arc.start + arc.end) / 2;
    // Just past the ring's edge, so the card never hides the slice it describes.
    const px = cx + Math.sin(middle) * (outer + 8);
    const py = cy - Math.cos(middle) * (outer + 8);
    const right = Math.sin(middle) >= 0;
    const left = right ? px + 8 : px - 8 - TOOLTIP_WIDTH;
    return {
      slice,
      left: clamp(left, 4, w - TOOLTIP_WIDTH - 4),
      top: clamp(py - TOOLTIP_HEIGHT / 2, 4, h - TOOLTIP_HEIGHT - 4)
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
  formatShares = (value: number | null) => (value == null ? '—' : shares.format(value));

  constructor() {
    // Ease from what's drawn to the live arcs whenever slices change size, appear or go.
    effect(() => {
      const to = this.targetArcs();
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
    this.hovered.set(key);
  }

  toggle(key: string): void {
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

  trackSlice = (_: number, drawn: DrawnSlice) => drawn.slice.key;
  trackLabel = (_: number, label: PlacedLabel) => label.key;
  trackKey = (_: number, slice: DonutSlice) => slice.key;

  private describe(slice: DonutSlice): string {
    const parts = [slice.kind === 'cash' ? 'Cash' : `${slice.label}, ${slice.name}`, formatMoney(slice.value)];
    if (slice.shares != null) {
      parts.push(`${shares.format(slice.shares)} share${slice.shares === 1 ? '' : 's'}`);
    }
    parts.push(`${formatShare(slice.share)} of account`);
    return parts.join(', ');
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
