import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Where a report is, or isn't yet. Every panel starts 'pending' (no report built for it); a
 * report sets the rest as it loads, and is projected into the panel once 'ready'.
 */
export type ReportState = 'pending' | 'loading' | 'empty' | 'error' | 'ready';

/**
 * Staff Dashboards: the card every report sits in - title, what it covers, and its loading, empty
 * and error states - so a report only has to draw its own table or chart. Until a report is built
 * the panel shows a placeholder that says where its data will come from.
 */
@Component({
  selector: 'app-report-panel',
  imports: [CommonModule],
  template: `
    <section class="panel" [attr.aria-labelledby]="headingId" [attr.aria-busy]="state === 'loading'">
      <header class="panel-header">
        <p class="panel-eyebrow" *ngIf="eyebrow">{{ eyebrow }}</p>
        <h2 class="panel-title" [id]="headingId">{{ heading }}</h2>
        <p class="panel-description" *ngIf="description">{{ description }}</p>
      </header>

      <div class="panel-body" [ngSwitch]="state">
        <div *ngSwitchCase="'pending'" class="placeholder" data-testid="report-pending">
          <span class="placeholder-badge">Coming soon</span>
          <p>This report hasn't been built yet.</p>
          <p class="source" *ngIf="source">Source: <code>{{ source }}</code></p>
        </div>
        <div *ngSwitchCase="'loading'" class="skeleton loading" aria-hidden="true"></div>
        <p *ngSwitchCase="'empty'" class="message">{{ message || 'Nothing to report for these filters.' }}</p>
        <div *ngSwitchCase="'error'" class="message error" role="alert">
          <p>{{ message || "Couldn't load this report." }}</p>
          <button type="button" (click)="retry.emit()">Try again</button>
        </div>
        <div *ngSwitchCase="'ready'" class="fade-in"><ng-content></ng-content></div>
      </div>
    </section>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .panel {
      height: 100%;
      display: flex;
      flex-direction: column;
      gap: 16px;
      padding: 20px;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
    }
    .panel-eyebrow {
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: var(--primary);
      margin-bottom: 6px;
    }
    .panel-title { font-size: 18px; font-weight: 600; color: var(--foreground); }
    .panel-description { margin-top: 4px; font-size: 14px; color: var(--muted-foreground); }
    .panel-body { flex: 1; display: flex; flex-direction: column; min-height: var(--panel-min-height, 160px); }
    .panel-body > * { flex: 1; }
    .placeholder {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 6px;
      padding: 24px;
      text-align: center;
      border: 1px dashed var(--border);
      border-radius: var(--radius-md);
      color: var(--muted-foreground);
      font-size: 14px;
    }
    .placeholder-badge {
      padding: 2px 10px;
      border-radius: 999px;
      background: var(--info-muted);
      color: var(--info);
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }
    .source { font-size: 12px; }
    code {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 12px;
      color: var(--foreground);
    }
    .loading { border-radius: var(--radius-md); opacity: 0.5; }
    .message {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 12px;
      color: var(--muted-foreground);
      font-size: 14px;
      text-align: center;
    }
    .message.error { color: var(--loss); }
    .message button {
      padding: 6px 14px;
      border-radius: 999px;
      background: var(--muted);
      color: var(--foreground);
      font-size: 13px;
      font-weight: 600;
    }
  `
})
export class ReportPanelComponent {
  private static nextId = 0;

  @Input({ required: true }) heading = '';
  @Input() eyebrow = '';
  @Input() description = '';
  /** Where the report's data will come from, shown on the placeholder, e.g. trading.executions. */
  @Input() source = '';
  @Input() state: ReportState = 'pending';
  /** Overrides the default empty or error wording. */
  @Input() message = '';
  @Output() readonly retry = new EventEmitter<void>();

  readonly headingId = `report-panel-${ReportPanelComponent.nextId++}`;
}
