import { Component, ElementRef, computed, input, output, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { formatMoney } from '../../shared/format';

/** Dragging snaps to this many dollars, and the arrow keys move by it. */
export const STEP = 10;
/** Holding Shift makes a drag or an arrow key this fine. */
export const FINE_STEP = 1;
/** Page Up and Page Down move by this much. */
export const PAGE_STEP = 100;

/**
 * Raise Cash: a vertical bar filled to the account's cash, with a handle for the cash the client
 * wants. The bar runs from $0 to everything the account could raise; above `limit` (what the
 * unlocked holdings can raise) it's hatched, since the handle can't go there.
 *
 * The handle is a slider: drag it (in $10 steps, or $1 with Shift) or use the keys. Every move is
 * emitted as asked, kept no lower than `min`; the tool caps it at `limit` and explains why, so a
 * key press or drag past the top still reaches it.
 */
@Component({
  selector: 'app-cash-bar',
  imports: [CommonModule],
  template: `
    <div class="bar" [class.disabled]="disabled()">
      <span class="scale top" aria-hidden="true">{{ money(scaleMax()) }}</span>
      <div #track class="track" data-testid="cash-track"
           (pointerdown)="onPointerDown($event)" (pointermove)="onPointerMove($event)"
           (pointerup)="onPointerUp($event)" (pointercancel)="onPointerUp($event)">
        <div *ngIf="limit() < scaleMax()" class="out-of-reach" data-testid="out-of-reach"
             [style.bottom.%]="percent(limit())" [style.height.%]="100 - percent(limit())"></div>
        <div class="fill cash" [style.height.%]="percent(min())"></div>
        <div class="fill raise" [style.bottom.%]="percent(min())"
             [style.height.%]="percent(value()) - percent(min())"></div>
        <div class="handle" role="slider" data-testid="cash-handle"
             [attr.tabindex]="disabled() ? null : 0"
             [attr.aria-disabled]="disabled() || null"
             aria-label="Target cash" aria-orientation="vertical"
             [attr.aria-valuemin]="min()" [attr.aria-valuemax]="limit()" [attr.aria-valuenow]="value()"
             [attr.aria-valuetext]="valueText()"
             [style.bottom.%]="percent(value())" (keydown)="onKey($event)">
          <span class="grip" aria-hidden="true"></span>
          <span class="label" aria-hidden="true">{{ money(value()) }}</span>
        </div>
      </div>
      <span class="scale bottom" aria-hidden="true">$0</span>
    </div>
  `,
  styles: [`
    :host { display: block; --bar-height: 320px; }
    .bar { display: flex; flex-direction: column; align-items: center; gap: 6px; }
    .scale { font-size: 11px; color: var(--muted-foreground); font-variant-numeric: tabular-nums; }
    .track {
      position: relative;
      width: 56px;
      height: var(--bar-height);
      border-radius: 14px;
      border: 1px solid var(--border);
      background: var(--muted);
      touch-action: none;
      cursor: ns-resize;
    }
    .fill { position: absolute; left: 0; right: 0; bottom: 0; border-radius: 0 0 13px 13px; }
    .fill.cash { background: color-mix(in srgb, var(--foreground) 30%, transparent); }
    .fill.raise { border-radius: 0; background: color-mix(in srgb, var(--primary) 55%, transparent); }
    .out-of-reach {
      position: absolute;
      left: 0;
      right: 0;
      border-radius: 13px 13px 0 0;
      background: repeating-linear-gradient(-45deg, transparent 0 6px, color-mix(in srgb, var(--border) 80%, transparent) 6px 8px);
    }
    .handle {
      position: absolute;
      left: -8px;
      right: -8px;
      height: 16px;
      margin-bottom: -8px;
      border-radius: 8px;
      background: var(--foreground);
      box-shadow: 0 2px 8px rgb(0 0 0 / 0.4);
      cursor: grab;
    }
    .handle:focus-visible { outline: 2px solid var(--ring); outline-offset: 3px; }
    .grip { position: absolute; left: 50%; top: 50%; width: 20px; height: 2px; margin: -1px 0 0 -10px; border-radius: 1px; background: var(--background); }
    .label {
      position: absolute;
      left: calc(100% + 10px);
      top: 50%;
      transform: translateY(-50%);
      padding: 2px 8px;
      border-radius: 999px;
      background: var(--card);
      border: 1px solid var(--border);
      font-size: 12px;
      font-weight: 600;
      color: var(--foreground);
      white-space: nowrap;
      font-variant-numeric: tabular-nums;
    }
    .disabled .track { cursor: not-allowed; opacity: 0.5; }
    .disabled .handle { cursor: not-allowed; box-shadow: none; }
    @media (max-width: 640px) { :host { --bar-height: 260px; } }
  `]
})
export class CashBarComponent {
  /** Current cash: the bottom of the handle's range. */
  readonly min = input.required<number>();
  /** The most cash the unlocked holdings can raise: the top of the handle's range. */
  readonly limit = input.required<number>();
  /** The most cash the account could raise with nothing locked: the top of the bar. */
  readonly scaleMax = input.required<number>();
  readonly value = input.required<number>();
  readonly disabled = input(false);
  readonly valueChange = output<number>();

  private readonly track = viewChild.required<ElementRef<HTMLElement>>('track');
  private dragging = false;

  readonly valueText = computed(() => `${formatMoney(this.value())} target`);
  money = formatMoney;

  /** Where an amount sits on the bar, as a percentage of its height. */
  percent(amount: number): number {
    const max = this.scaleMax();
    return max > 0 ? Math.min(Math.max(amount / max, 0), 1) * 100 : 0;
  }

  onPointerDown(event: PointerEvent): void {
    if (this.disabled() || event.button !== 0) {
      return;
    }
    event.preventDefault();
    this.dragging = true;
    this.track().nativeElement.setPointerCapture?.(event.pointerId);
    this.track().nativeElement.querySelector<HTMLElement>('.handle')?.focus();
    this.dragTo(event);
  }

  onPointerMove(event: PointerEvent): void {
    if (this.dragging) {
      this.dragTo(event);
    }
  }

  onPointerUp(event: PointerEvent): void {
    if (this.dragging) {
      this.dragging = false;
      this.track().nativeElement.releasePointerCapture?.(event.pointerId);
    }
  }

  onKey(event: KeyboardEvent): void {
    if (this.disabled()) {
      return;
    }
    const step = event.shiftKey ? FINE_STEP : STEP;
    const moves: Record<string, () => number> = {
      ArrowUp: () => this.value() + step,
      ArrowRight: () => this.value() + step,
      ArrowDown: () => this.value() - step,
      ArrowLeft: () => this.value() - step,
      PageUp: () => this.value() + PAGE_STEP,
      PageDown: () => this.value() - PAGE_STEP,
      Home: () => this.min(),
      End: () => this.limit()
    };
    const move = moves[event.key];
    if (move) {
      event.preventDefault();
      this.valueChange.emit(this.clamp(move()));
    }
  }

  /** The amount under the pointer, snapped to $10 ($1 with Shift). */
  private dragTo(event: PointerEvent): void {
    const rect = this.track().nativeElement.getBoundingClientRect();
    if (!rect.height) {
      return;
    }
    const step = event.shiftKey ? FINE_STEP : STEP;
    const raw = ((rect.bottom - event.clientY) / rect.height) * this.scaleMax();
    this.valueChange.emit(this.clamp(Math.round(raw / step) * step));
  }

  /** Kept no lower than current cash, to the cent. */
  private clamp(amount: number): number {
    return Math.round(Math.max(amount, this.min()) * 100) / 100;
  }
}
