import { Component, Input, computed, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LifecycleStage, elapsedSince, totalDuration } from './lifecycle';

/**
 * Staff Dashboards: an order's lifecycle as a vertical timeline - each step with its timestamp to
 * the millisecond, the time since the step before, and the table it was read from. Steps with no
 * timestamp yet show as awaiting data; steps that can't happen show as not applicable.
 */
@Component({
  selector: 'app-lifecycle-timeline',
  imports: [CommonModule],
  template: `
    <p class="total" *ngIf="total() as total">Submission to last step: <span class="num">{{ total }}</span></p>
    <ol class="timeline" aria-label="Order lifecycle">
      <li *ngFor="let stage of view(); let i = index" class="stage" data-testid="lifecycle-stage"
          [attr.data-stage]="stage.key" [attr.data-state]="stage.state">
        <span class="marker" aria-hidden="true"></span>
        <div class="stage-main">
          <div class="stage-heading">
            <span class="stage-label">{{ stage.label }}</span>
            <span class="elapsed num" *ngIf="stage.elapsed">{{ stage.elapsed }}</span>
          </div>
          <p class="stage-description">{{ stage.description }}</p>
          <p class="stage-detail" *ngIf="stage.detail">{{ stage.detail }}</p>
          <p class="stage-source"><code>{{ stage.source }}</code></p>
        </div>
        <div class="stage-time">
          <ng-container [ngSwitch]="stage.state">
            <ng-container *ngSwitchCase="'done'">
              <time class="num" [attr.datetime]="stage.at">{{ stage.at | date: 'yyyy-MM-dd HH:mm:ss.SSS ZZZZZ' }}</time>
            </ng-container>
            <span *ngSwitchCase="'skipped'" class="text-muted">Not applicable</span>
            <span *ngSwitchDefault class="text-muted" aria-label="Awaiting data">—</span>
          </ng-container>
        </div>
      </li>
    </ol>
  `,
  styles: `
    :host { display: block; }
    .total { font-size: 13px; color: var(--muted-foreground); margin-bottom: 12px; }
    .total .num { color: var(--foreground); font-weight: 600; }
    .timeline { list-style: none; position: relative; }
    .stage {
      position: relative;
      display: grid;
      grid-template-columns: 20px minmax(0, 1fr) auto;
      gap: 12px;
      padding-bottom: 20px;
    }
    .stage:last-child { padding-bottom: 0; }
    /* The rail joining each marker to the next. */
    .stage:not(:last-child)::before {
      content: '';
      position: absolute;
      left: 9px;
      top: 18px;
      bottom: 0;
      width: 2px;
      background: var(--border);
    }
    .marker {
      width: 12px;
      height: 12px;
      margin: 4px 0 0 4px;
      border-radius: 50%;
      border: 2px solid var(--muted-foreground);
      background: var(--background);
      position: relative;
    }
    [data-state='done'] .marker { border-color: var(--primary); background: var(--primary); }
    [data-state='done']:not(:last-child)::before { background: color-mix(in srgb, var(--primary) 50%, var(--border)); }
    [data-state='skipped'] .marker { border-style: dashed; opacity: 0.6; }
    [data-state='skipped'] .stage-main { opacity: 0.6; }
    .stage-heading { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
    .stage-label { font-size: 15px; font-weight: 600; color: var(--foreground); }
    .elapsed {
      padding: 1px 8px;
      border-radius: 999px;
      background: var(--muted);
      color: var(--muted-foreground);
      font-size: 12px;
      font-weight: 500;
    }
    .stage-description { font-size: 13px; color: var(--muted-foreground); margin-top: 2px; }
    .stage-detail { font-size: 13px; color: var(--foreground); margin-top: 4px; }
    .stage-source { margin-top: 4px; }
    code {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 11px;
      color: var(--muted-foreground);
    }
    .stage-time { font-size: 13px; text-align: right; white-space: nowrap; padding-top: 1px; }
    @media (max-width: 640px) {
      .stage { grid-template-columns: 20px minmax(0, 1fr); }
      .stage-time { grid-column: 2; text-align: left; }
    }
  `
})
export class LifecycleTimelineComponent {
  private readonly stages = signal<LifecycleStage[]>([]);

  /** The stages in order, from emptyLifecycle() with whatever has been recorded filled in. */
  @Input({ required: true }) set lifecycle(stages: LifecycleStage[]) {
    this.stages.set(stages);
  }

  readonly view = computed(() => {
    const stages = this.stages();
    return stages.map((stage, i) => ({
      ...stage,
      state: stage.skipped ? 'skipped' : stage.at ? 'done' : 'awaiting',
      elapsed: stage.skipped ? null : elapsedSince(stages, i)
    }));
  });

  readonly total = computed(() => totalDuration(this.stages()));
}
