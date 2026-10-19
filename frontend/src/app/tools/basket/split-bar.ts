import { Component, ElementRef, computed, input, output, viewChild } from '@angular/core';
import { formatMoney } from '../../shared/format';
import { SLICE_PALETTE } from '../rebalance/allocation';

export interface SplitSegment {
  symbol: string;
  amountCents: number;
}

/** Keeps a drag on its divider when the pointer strays; a pointer that has already gone can't be held. */
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

/** Every segment is drawn at least this wide, so a small or $0 stock can still be seen and grabbed. */
const MIN_VISIBLE_SHARE = 0.04;

/**
 * Basket Builder: the amount as a horizontal bar, one segment per stock, with a divider between
 * each pair. Dragging a divider, or focusing it and pressing the arrow keys, moves money between
 * the two stocks beside it; the parent applies the change and passes the new split back.
 * Drawn like the Rebalance Donut: a thin track in the donut's slice colours, round handles, and a
 * legend underneath that names every segment however narrow it is.
 */
@Component({
  selector: 'app-split-bar',
  template: `
    <div #bar class="bar" [class.disabled]="disabled()" data-testid="split-bar">
      @for (s of view(); track s.symbol; let i = $index) {
        <div class="segment" [style.left.%]="s.start * 100" [style.width.%]="s.width * 100" [style.background]="s.color"
             [class.first]="i === 0" [class.last]="i === view().length - 1" [attr.data-symbol]="s.symbol"></div>
      }
      @for (d of dividers(); track d.index) {
        <span class="divider" role="slider" tabindex="0" [style.left.%]="d.position"
              [attr.aria-label]="'Divider between ' + d.before + ' and ' + d.after"
              aria-valuemin="0" aria-valuemax="100" [attr.aria-valuenow]="d.valueNow" [attr.aria-valuetext]="d.valueText"
              [attr.aria-disabled]="disabled() ? true : null" [attr.data-divider]="d.index"
              (pointerdown)="startDrag($event, d.index)" (pointermove)="drag($event, d.index)"
              (pointerup)="endDrag($event)" (pointercancel)="endDrag($event)" (keydown)="onKey($event, d.index)">
          <span class="knob" aria-hidden="true"></span>
        </span>
      }
    </div>

    <ul class="legend" aria-label="Basket split" data-testid="split-legend">
      @for (s of view(); track s.symbol) {
        <li [attr.data-symbol]="s.symbol">
          <span class="swatch" [style.background]="s.color" aria-hidden="true"></span>
          <span class="l-symbol">{{ s.symbol }}</span>
          <span class="num">{{ s.percent }}</span>
          <span class="num l-money">{{ s.money }}</span>
        </li>
      }
    </ul>
  `,
  styles: [`
    :host { display: block; }
    /* A thin track, like the donut's ring; the handles sit on it. */
    .bar { position: relative; height: 12px; margin: 10px 0; border-radius: 999px; background: var(--surface); }
    .bar.disabled { opacity: 0.55; }
    .segment { position: absolute; top: 0; bottom: 0; box-sizing: border-box; border-right: 2px solid var(--card); }
    .segment.first { border-top-left-radius: 999px; border-bottom-left-radius: 999px; }
    .segment.last { border-top-right-radius: 999px; border-bottom-right-radius: 999px; border-right: 0; }
    .divider {
      position: absolute; top: 50%; width: 28px; height: 28px; margin: -14px 0 0 -14px;
      display: grid; place-items: center; cursor: ew-resize; touch-action: none; border-radius: 50%;
    }
    .divider:focus-visible { outline: 2px solid var(--foreground); outline-offset: 0; }
    .knob { width: 16px; height: 16px; border-radius: 50%; background: var(--foreground); box-shadow: 0 0 0 3px var(--card); }
    .divider:hover .knob, .divider:focus-visible .knob { transform: scale(1.15); }
    .disabled .divider { cursor: default; }
    .disabled .knob { transform: none; }
    .legend { display: flex; flex-wrap: wrap; gap: 6px 18px; margin: 4px 0 0; padding: 0; list-style: none; font-size: 13px; }
    .legend li { display: inline-flex; align-items: center; gap: 6px; color: var(--muted-foreground); }
    .swatch { width: 12px; height: 12px; border-radius: 4px; }
    .l-symbol { font-weight: 600; color: var(--foreground); }
    .l-money { color: var(--foreground); }
    @media (prefers-reduced-motion: no-preference) { .knob { transition: transform 0.12s ease; } }
  `]
})
export class SplitBarComponent {
  readonly segments = input.required<SplitSegment[]>();
  /** Shown but not draggable, e.g. before there's an amount to split. */
  readonly disabled = input(false);
  /** A divider dragged towards a point, in cents from the bar's start. */
  readonly dragged = output<{ divider: number; positionCents: number }>();
  /** A divider stepped by a key: +1 gives the stock before it 1% more. */
  readonly stepped = output<{ divider: number; steps: number }>();

  private readonly bar = viewChild.required<ElementRef<HTMLElement>>('bar');
  private dragging: number | null = null;

  private readonly total = computed(() => this.segments().reduce((sum, s) => sum + s.amountCents, 0));

  /**
   * Each segment's real share of the amount and the share of the bar it's drawn at: the real share,
   * widened to the minimum when it's smaller, with the rest scaled to fit.
   */
  readonly view = computed(() => {
    const total = this.total();
    const count = this.segments().length;
    // With nothing to split yet, the bar still shows every stock, evenly.
    const shares = this.segments().map(s => (total > 0 ? s.amountCents / total : 1 / count));
    const widened = shares.map(share => Math.max(share, MIN_VISIBLE_SHARE));
    const scale = widened.reduce((sum, w) => sum + w, 0);
    let start = 0;
    let realStart = 0;
    return this.segments().map((s, i) => {
      const share = shares[i];
      const width = widened[i] / scale;
      const segment = { start, width, realStart };
      start += width;
      realStart += s.amountCents;
      return {
        symbol: s.symbol, ...segment, share, color: SLICE_PALETTE[i % SLICE_PALETTE.length],
        percent: `${Math.round(share * 100)}%`, money: formatMoney(s.amountCents / 100)
      };
    });
  });

  readonly dividers = computed(() => {
    const view = this.view();
    let drawnAt = 0;
    let realAt = 0;
    return view.slice(0, -1).map((s, i) => {
      drawnAt += s.width;
      realAt += s.share;
      return {
        index: i, position: drawnAt * 100, valueNow: Math.round(realAt * 100),
        before: s.symbol, after: view[i + 1].symbol,
        valueText: `${s.symbol} ${s.percent}, ${view[i + 1].symbol} ${view[i + 1].percent}`
      };
    });
  });

  startDrag(event: PointerEvent, divider: number): void {
    if (this.disabled()) {
      return;
    }
    event.preventDefault();
    capture(event, true);
    (event.currentTarget as HTMLElement).focus();
    this.dragging = divider;
  }

  drag(event: PointerEvent, divider: number): void {
    if (this.dragging !== divider) {
      return;
    }
    // Map the pointer across the two segments beside the divider, drawn width to real amount, so
    // the divider stays under the pointer even where a narrow segment is drawn wider than it is.
    const rect = this.bar().nativeElement.getBoundingClientRect();
    const x = (event.clientX - rect.left) / rect.width;
    const before = this.view()[divider];
    const after = this.view()[divider + 1];
    const drawnEnd = after.start + after.width;
    const realEnd = after.realStart + this.segments()[divider + 1].amountCents;
    const across = Math.min(Math.max((x - before.start) / (drawnEnd - before.start), 0), 1);
    this.dragged.emit({ divider, positionCents: before.realStart + across * (realEnd - before.realStart) });
  }

  endDrag(event: PointerEvent): void {
    capture(event, false);
    this.dragging = null;
  }

  /** Arrow keys move a divider 1%: right and up give the stock before it more. */
  onKey(event: KeyboardEvent, divider: number): void {
    const steps = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1 }[event.key];
    if (steps === undefined || this.disabled()) {
      return;
    }
    event.preventDefault();
    this.stepped.emit({ divider, steps });
  }
}
