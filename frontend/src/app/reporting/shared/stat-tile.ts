import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Staff Dashboards: one headline figure, styled like the trading dashboard's summary stats. Shows
 * a dash until a report supplies the value.
 */
@Component({
  selector: 'app-stat-tile',
  imports: [CommonModule],
  template: `
    <div class="stat">
      <span class="stat-label">{{ label }}</span>
      <span class="stat-value num" [class.text-muted]="value === null">{{ value ?? '—' }}</span>
      <span class="stat-hint" *ngIf="hint">{{ hint }}</span>
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .stat {
      height: 100%;
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 14px 16px;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      min-width: 0;
    }
    .stat-label { font-size: 12px; color: var(--muted-foreground); }
    .stat-value {
      font-size: 17px;
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .stat-hint { font-size: 12px; color: var(--muted-foreground); }
  `
})
export class StatTileComponent {
  @Input({ required: true }) label = '';
  /** The formatted figure, or null until a report provides it. */
  @Input() value: string | null = null;
  @Input() hint = '';
}
