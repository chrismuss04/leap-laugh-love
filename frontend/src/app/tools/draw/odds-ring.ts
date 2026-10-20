import { Component, computed, input } from '@angular/core';
import { RollingNumberComponent } from '../../shared/rolling-number';

/** "34%", or "<1%" and ">99%" at the ends, where a rounded figure would promise too much. */
export function formatChance(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) {
    return '—';
  }
  const percent = value * 100;
  if (percent > 0 && percent < 1) {
    return '<1%';
  }
  if (percent > 99 && percent < 100) {
    return '>99%';
  }
  return `${Math.round(percent)}%`;
}

/**
 * Draw Your Trade: one chance as a ring that fills to it, the figure rolling in the middle and what
 * it's the chance of underneath.
 */
@Component({
  selector: 'app-odds-ring',
  imports: [RollingNumberComponent],
  template: `
    <div class="ring" [attr.data-tone]="tone()" role="img" [attr.aria-label]="label() + ': ' + text()">
      <svg viewBox="0 0 64 64" aria-hidden="true">
        <circle class="track" cx="32" cy="32" r="27" pathLength="100" />
        <circle class="fill" cx="32" cy="32" r="27" pathLength="100" [style.stroke-dashoffset]="100 - percent()" />
      </svg>
      <span class="figure num" [appRollingNumber]="value()" [format]="format"></span>
    </div>
    <span class="label">{{ label() }}</span>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; align-items: center; gap: 8px; min-width: 0; text-align: center; }
    .ring { --tone: var(--info); position: relative; width: 72px; height: 72px; }
    .ring[data-tone='gain'] { --tone: var(--gain); }
    .ring[data-tone='loss'] { --tone: var(--loss); }
    .ring[data-tone='entry'] { --tone: var(--primary); }
    svg { width: 100%; height: 100%; transform: rotate(-90deg); }
    circle { fill: none; stroke-width: 6; }
    .track { stroke: var(--muted); }
    .fill {
      stroke: var(--tone);
      stroke-linecap: round;
      stroke-dasharray: 100;
      filter: drop-shadow(0 0 6px color-mix(in srgb, var(--tone) 55%, transparent));
      transition: stroke-dashoffset 0.9s cubic-bezier(0.2, 0, 0, 1);
      animation: fill-in 1.1s cubic-bezier(0.2, 0, 0, 1) both;
    }
    .figure {
      position: absolute;
      inset: 0;
      display: grid;
      place-items: center;
      font-size: 17px;
      font-weight: 650;
      color: var(--foreground);
    }
    .label { font-size: 12px; line-height: 1.35; color: var(--muted-foreground); max-width: 140px; }
    @keyframes fill-in { from { stroke-dashoffset: 100; } }
    @media (prefers-reduced-motion: reduce) { .fill { transition: none; animation: none; } }
  `]
})
export class OddsRingComponent {
  /** The chance, 0-1. */
  readonly value = input<number | null>(null);
  readonly label = input('');
  readonly tone = input<'gain' | 'loss' | 'entry' | 'info'>('info');

  readonly percent = computed(() => Math.min(Math.max((this.value() ?? 0) * 100, 0), 100));
  readonly text = computed(() => formatChance(this.value()));

  format = formatChance;
}
