import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Staff Dashboards: one headline figure, styled like the trading dashboard's summary stats. Shows
 * a dash until a report supplies the value, and how it moved when a report supplies a trend.
 */
@Component({
  selector: 'app-stat-tile',
  imports: [CommonModule],
  template: `
    <div class="stat">
      <span class="stat-label">{{ label }}</span>
      <span class="stat-value num" [class.text-muted]="value === null">{{ value ?? '—' }}</span>
      <span class="stat-hint" *ngIf="hint">{{ hint }}</span>
      <span class="stat-trend" *ngIf="trend" [class.text-gain]="trendDirection === 'gain'"
            [class.text-loss]="trendDirection === 'loss'">{{ trend }}</span>
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
    .stat-trend { margin-top: auto; padding-top: 4px; font-size: 12px; color: var(--muted-foreground); }
    /* Set here, not left to the global text-gain/text-loss: this component's own colour outranks them. */
    .stat-trend.text-gain { color: var(--gain); }
    .stat-trend.text-loss { color: var(--loss); }
  `
})
export class StatTileComponent {
  @Input({ required: true }) label = '';
  /** The formatted figure, or null until a report provides it. */
  @Input() value: string | null = null;
  @Input() hint = '';
  /** How the figure moved, e.g. "+12.00% vs previous period"; signed, so colour isn't the only cue. */
  @Input() trend = '';
  @Input() trendDirection: 'gain' | 'loss' | 'flat' = 'flat';
}
