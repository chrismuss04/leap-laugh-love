import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AllocationSlice } from '../tools-context';

interface Arc {
  key: string;
  color: string;
  dash: string;
  offset: number;
}

const round = (value: number) => Math.round(value * 100) / 100;

/**
 * A small allocation donut. Each slice is a stroked circle cut to its share of a 100-unit
 * circumference, so slices resize smoothly as live prices move.
 */
@Component({
    selector: 'app-mini-donut',
    imports: [CommonModule],
    template: `
    <svg viewBox="0 0 42 42" [attr.width]="size" [attr.height]="size" role="img" [attr.aria-label]="label">
      <circle class="track" cx="21" cy="21" r="15.915" />
      <circle *ngFor="let arc of arcs; trackBy: trackByKey" class="arc" cx="21" cy="21" r="15.915"
              [attr.stroke]="arc.color" [attr.stroke-dasharray]="arc.dash" [attr.stroke-dashoffset]="arc.offset" />
    </svg>
  `,
    styles: [`
    :host { display: inline-flex; }
    circle { fill: none; stroke-width: 6; }
    .track { stroke: var(--muted); }
    .arc { transition: stroke-dasharray 0.6s ease, stroke-dashoffset 0.6s ease; }
    @media (prefers-reduced-motion: reduce) { .arc { transition: none; } }
  `]
})
export class MiniDonutComponent implements OnChanges {
  @Input() slices: AllocationSlice[] = [];
  @Input() size = 96;

  arcs: Arc[] = [];
  label = 'No allocation yet';

  ngOnChanges(): void {
    let start = 0;
    // A small gap between slices keeps neighbours apart; a lone slice closes the ring.
    const gap = this.slices.length > 1 ? 0.8 : 0;
    this.arcs = this.slices.map(slice => {
      const length = slice.share * 100;
      const drawn = round(Math.max(length - gap, 0));
      // Start at 12 o'clock: the dash pattern begins at 3 o'clock, a quarter turn back.
      const arc = {
        key: slice.key,
        color: slice.color,
        dash: `${drawn} ${round(100 - drawn)}`,
        offset: round(25 - start)
      };
      start += length;
      return arc;
    });
    this.label = this.slices.length
      ? 'Allocation: ' + this.slices.map(s => `${s.label} ${Math.round(s.share * 100)}%`).join(', ')
      : 'No allocation yet';
  }

  trackByKey(_: number, arc: Arc): string {
    return arc.key;
  }
}
