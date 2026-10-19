import { Component, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { formatShare } from './allocation';
import { BALANCED_WITHIN, TradePreview } from './trade-preview';

const dollars = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
/** Drift this large fills the meter. */
const DRIFT_SCALE = 0.25;

/**
 * Rebalance Donut: the trades the targets take, listed live beside the donut as the client drags
 * or types ("Sell 4 AAPL ≈ $732"), with how far the account has drifted from its targets, any
 * warnings from the order-plan engine, and the button that puts the trades in the plan.
 */
@Component({
  selector: 'app-trade-preview-panel',
  imports: [CommonModule],
  template: `
    <section class="panel" aria-labelledby="trades-title" data-testid="trade-preview">
      <header>
        <h2 id="trades-title">Trades</h2>
        <div class="drift" [class.ok]="preview().balanced" data-testid="drift"
             [attr.title]="'How much of the account is off target'">
          <span class="drift-label">Drift</span>
          <span class="drift-bar" aria-hidden="true"><span [style.width.%]="driftFill()"></span></span>
          <strong class="num">{{ share(preview().drift) }}</strong>
        </div>
      </header>

      <div *ngIf="preview().balanced; else trades" class="balanced" data-testid="already-balanced">
        <svg viewBox="0 0 24 24" width="28" height="28" aria-hidden="true">
          <circle cx="12" cy="12" r="10" /><path d="M7.5 12.5l3 3 6-6.5" />
        </svg>
        <div>
          <strong>Already balanced</strong>
          <p>Every slice is within {{ within }} of its target. Move a handle to plan some trades.</p>
        </div>
      </div>

      <ng-template #trades>
        <ol *ngIf="preview().orders.length; else none" class="trades" aria-label="Trades to reach your targets">
          <li *ngFor="let order of preview().orders; trackBy: trackOrder" [attr.data-trade]="order.symbol"
              [class.buy]="order.side === 'BUY'" [class.sell]="order.side === 'SELL'">
            <span class="side" aria-hidden="true">{{ order.side === 'BUY' ? '↑' : '↓' }}</span>
            <span class="what">
              <span class="verb">{{ order.side === 'BUY' ? 'Buy' : 'Sell' }}</span>&ngsp;
              <span class="num">{{ order.quantity }}</span>&ngsp;<strong>{{ order.symbol }}</strong>
            </span>
            <span class="value num">≈ {{ whole(order.estimatedValue) }}</span>
          </li>
        </ol>
        <ng-template #none>
          <p class="none" data-testid="no-trades">No whole-share trades get closer to these targets.</p>
        </ng-template>
      </ng-template>

      <ul *ngIf="preview().warnings.length" class="warnings" data-testid="trade-warnings">
        <li *ngFor="let warning of preview().warnings" [attr.data-warning]="warning.code">
          <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M8 2l6.5 11.5h-13z" /><path d="M8 6.5v3M8 11.5v.2" /></svg>
          <span>{{ warning.message }}<span *ngIf="warning.hint" class="hint"> {{ warning.hint }}</span></span>
        </li>
      </ul>

      <footer>
        <dl *ngIf="preview().orders.length" class="totals">
          <div *ngIf="preview().sellTotal"><dt>Sells</dt><dd class="num">≈ {{ whole(preview().sellTotal) }}</dd></div>
          <div *ngIf="preview().buyTotal"><dt>Buys</dt><dd class="num">≈ {{ whole(preview().buyTotal) }}</dd></div>
          <div><dt>Cash after</dt><dd class="num" data-testid="cash-after">≈ {{ whole(preview().cashAfter) }}</dd></div>
        </dl>
        <button type="button" class="primary" data-testid="add-to-plan" [disabled]="!preview().orders.length || inPlan()"
                (click)="add.emit()">
          <ng-container *ngIf="inPlan(); else label">
            <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M3.5 8.5l3 3 6-7" /></svg> In your plan
          </ng-container>
          <ng-template #label>{{ hasPlanned() ? 'Update plan' : 'Add to plan' }}</ng-template>
        </button>
        <p *ngIf="hasPlanned() && !inPlan() && preview().orders.length" class="replaces">
          Replaces the Rebalance trades already in your plan.
        </p>
      </footer>
    </section>
  `,
  styles: [`
    :host { display: block; --subtle: #a1a1ae; }
    .panel {
      display: flex;
      flex-direction: column;
      gap: 14px;
      padding: 16px;
      border-radius: var(--radius-lg);
      border: 1px solid var(--border);
      background: color-mix(in srgb, var(--card) 70%, transparent);
    }
    header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
    h2 { font-size: 16px; font-weight: 600; color: var(--foreground); }
    .drift { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: var(--subtle); }
    .drift strong { min-width: 40px; text-align: right; font-size: 13px; color: var(--warning); }
    .drift.ok strong { color: var(--gain); }
    .drift-bar { width: 64px; height: 4px; border-radius: 999px; background: var(--muted); overflow: hidden; }
    .drift-bar span { display: block; height: 100%; border-radius: inherit; background: var(--warning); transition: width 0.2s ease; }
    .drift.ok .drift-bar span { background: var(--gain); }
    .balanced {
      display: flex;
      gap: 12px;
      align-items: flex-start;
      padding: 14px;
      border-radius: var(--radius-md);
      background: var(--gain-muted);
    }
    .balanced svg { flex: none; fill: none; stroke: var(--gain); stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
    .balanced strong { color: var(--foreground); font-size: 14px; }
    .balanced p, .none { margin-top: 2px; font-size: 13px; color: var(--subtle); }
    .trades { display: flex; flex-direction: column; gap: 2px; list-style: none; max-height: 248px; overflow-y: auto; }
    .trades li {
      display: grid;
      grid-template-columns: 26px minmax(0, 1fr) auto;
      align-items: center;
      gap: 10px;
      padding: 8px 10px;
      border-radius: var(--radius-md);
      font-size: 14px;
      color: var(--foreground);
      animation: trade-in 0.18s ease-out;
    }
    .trades li:hover { background: var(--surface-hover); }
    .side {
      display: grid;
      place-items: center;
      width: 26px;
      height: 26px;
      border-radius: 50%;
      font-weight: 700;
    }
    .buy .side { color: var(--gain); background: var(--gain-muted); }
    .sell .side { color: var(--loss); background: var(--loss-muted); }
    .what { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
    .what .verb { color: var(--subtle); }
    .value { color: var(--foreground); font-weight: 500; }
    .warnings { display: flex; flex-direction: column; gap: 6px; list-style: none; }
    .warnings li {
      display: flex;
      gap: 8px;
      padding: 10px 12px;
      border-radius: var(--radius-md);
      background: var(--warning-muted);
      font-size: 13px;
      line-height: 1.45;
      color: var(--foreground);
    }
    .warnings svg { flex: none; margin-top: 2px; fill: none; stroke: var(--warning); stroke-width: 1.6; stroke-linejoin: round; stroke-linecap: round; }
    .hint { color: var(--subtle); }
    footer { display: flex; flex-direction: column; gap: 10px; padding-top: 12px; border-top: 1px solid var(--border); }
    .totals { display: grid; gap: 4px; }
    .totals div { display: flex; justify-content: space-between; font-size: 13px; }
    .totals dt { color: var(--subtle); }
    .totals dd { color: var(--foreground); font-weight: 500; }
    .primary {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      padding: 10px 18px;
      border-radius: 999px;
      background: var(--primary);
      color: var(--primary-foreground);
      font-size: 14px;
      font-weight: 600;
      transition: filter 0.15s ease, transform 0.15s ease;
    }
    .primary svg { fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
    .primary:hover:not(:disabled) { filter: brightness(1.08); }
    .primary:active:not(:disabled) { transform: scale(0.98); }
    .primary:disabled { background: var(--muted); color: var(--subtle); cursor: default; }
    .replaces { font-size: 12px; color: var(--subtle); text-align: center; }
    @keyframes trade-in { from { opacity: 0; transform: translateX(-4px); } }
    @media (prefers-reduced-motion: reduce) {
      .trades li { animation: none; }
      .drift-bar span, .primary { transition: none; }
    }
  `]
})
export class TradePreviewPanelComponent {
  readonly preview = input.required<TradePreview>();
  /** Whether the tray already holds Rebalance trades. */
  readonly hasPlanned = input(false);
  /** Whether those trades are exactly these. */
  readonly inPlan = input(false);
  readonly add = output<void>();

  readonly driftFill = computed(() => Math.min(this.preview().drift / DRIFT_SCALE, 1) * 100);
  readonly within = `${BALANCED_WITHIN * 100}%`;

  share = (value: number) => formatShare(value);
  whole = (value: number) => dollars.format(value);
  trackOrder = (_: number, order: { symbol: string; side: string }) => order.side + order.symbol;
}
