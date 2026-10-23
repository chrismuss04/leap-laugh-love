import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { ExecutionRecord, TradeOpsService, TradeRecords } from '../../services/trade-ops';
import { isOrderId } from '../filters/audit';
import { ReportPanelComponent, ReportState } from '../shared/report-panel';
import { LifecycleTimelineComponent } from './lifecycle-timeline';
import { LifecycleStage, OrderOutcome, emptyLifecycle, fromTimeline, withOutcome } from './lifecycle';

const NO_DATA = 'No data available';
const OUTCOMES: readonly string[] = ['FILLED', 'REJECTED', 'CANCELLED', 'EXPIRED'] satisfies OrderOutcome[];

/**
 * Staff Dashboards: one order retraced end to end - every step from submission to the reporting
 * store with its timestamp, then the execution, cash ledger and position records behind them. The
 * order and its records load in one request and its timeline in another, both logged by order-app;
 * the timeline stamps the stages, with the market quote the order was priced from, and the records
 * give the outcome and order type that decide which stages apply. The order's steps can be
 * downloaded as CSV from order-app, which logs each download.
 */
@Component({
  selector: 'app-order-lifecycle',
  imports: [CommonModule, RouterLink, ReportPanelComponent, LifecycleTimelineComponent],
  templateUrl: './order-lifecycle.html',
  styleUrls: ['../shared/dashboard.css', './order-lifecycle.css']
})
export class OrderLifecycleComponent {
  readonly orderId = toSignal(inject(ActivatedRoute).paramMap.pipe(map(params => params.get('orderId') ?? '')),
    { requireSync: true });
  readonly validId = computed(() => isOrderId(this.orderId()));

  /** The stages with their recorded timestamps; every stage awaits data until the timeline loads. */
  readonly recorded = signal<LifecycleStage[]>(emptyLifecycle());
  /** True when the timeline failed to load, so retrying the records retries it too. */
  private readonly timelineFailed = signal(false);
  /** The order's final status, once known; it decides which stages apply. */
  readonly outcome = signal<OrderOutcome | null>(null);
  /** The order's type, once known: a market order never waits for its price. */
  readonly orderType = signal<string | null>(null);

  readonly stages = computed(() => withOutcome(this.recorded(), this.outcome(), this.orderType()));

  readonly records = signal<TradeRecords | null>(null);
  /** Where the order's records are; once 'ready', each ledger panel is empty or not by its own rows. */
  readonly recordsState = signal<ReportState>('loading');
  readonly recordsMessage = signal('');

  readonly executionsState = computed(() => this.listState(this.records()?.executions));
  readonly cashEntriesState = computed(() => this.listState(this.records()?.cashEntries));
  readonly positionMovementsState = computed(() => this.listState(this.records()?.positionMovements));
  readonly panelMessage = computed(() => this.recordsState() === 'ready' ? NO_DATA : this.recordsMessage());

  /** What the order filled at: the fill's price, or the quantity-weighted average over several; null if unfilled. */
  readonly fillPrice = computed(() => averageFillPrice(this.records()?.executions ?? []));

  readonly downloading = signal(false);
  readonly downloadError = signal('');

  private readonly tradeOps = inject(TradeOpsService);

  constructor() {
    effect(() => {
      const orderId = this.orderId();
      if (this.validId()) {
        untracked(() => {
          this.loadRecords(orderId);
          this.loadTimeline(orderId);
        });
      }
    });
  }

  retryRecords(): void {
    this.loadRecords(this.orderId());
    if (this.timelineFailed()) {
      this.loadTimeline(this.orderId());
    }
  }

  downloadCsv(): void {
    const orderId = this.orderId();
    this.downloading.set(true);
    this.downloadError.set('');
    this.tradeOps.timelineCsv(orderId).subscribe({
      next: csv => {
        const link = document.createElement('a');
        link.href = URL.createObjectURL(csv);
        link.download = `trade-${orderId}.csv`;
        link.click();
        URL.revokeObjectURL(link.href);
        this.downloading.set(false);
      },
      error: err => {
        this.downloadError.set(err.status === 404 ? 'No order has this id.' : "Couldn't download the CSV.");
        this.downloading.set(false);
      }
    });
  }

  private loadRecords(orderId: string): void {
    this.records.set(null);
    this.recordsState.set('loading');
    this.tradeOps.records(orderId).subscribe({
      next: records => {
        this.records.set(records);
        this.recordsState.set('ready');
        const status = records.order.summary.status;
        this.outcome.set(OUTCOMES.includes(status) ? status as OrderOutcome : null);
        this.orderType.set(records.order.orderType);
      },
      error: err => {
        // Retrying can't find an order that isn't there, so a 404 is a message, not an error.
        this.recordsState.set(err.status === 404 ? 'empty' : 'error');
        this.recordsMessage.set(err.status === 404 ? 'No order has this id.' : "Couldn't load this order's records.");
      }
    });
  }

  // The timeline panel has no error state of its own: if this fails its stages stay awaiting data,
  // and the records panels, which load separately, say what went wrong.
  private loadTimeline(orderId: string): void {
    this.timelineFailed.set(false);
    this.tradeOps.timeline(orderId).subscribe({
      next: timeline => this.recorded.set(fromTimeline(timeline.steps)),
      error: () => {
        this.recorded.set(emptyLifecycle());
        this.timelineFailed.set(true);
      }
    });
  }

  private listState(rows: unknown[] | undefined): ReportState {
    const state = this.recordsState();
    if (state !== 'ready') { return state; }
    return rows?.length ? 'ready' : 'empty';
  }
}

function averageFillPrice(executions: ExecutionRecord[]): number | null {
  const fills = executions.filter(execution => execution.status === 'FILLED' && execution.fillPrice !== null);
  const quantity = fills.reduce((sum, fill) => sum + (fill.fillQuantity ?? 0), 0);
  if (!fills.length || !quantity) { return null; }
  return fills.reduce((sum, fill) => sum + fill.fillPrice! * (fill.fillQuantity ?? 0), 0) / quantity;
}
