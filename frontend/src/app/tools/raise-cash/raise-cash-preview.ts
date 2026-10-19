import { Component, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { formatMoney, formatSignedMoney } from '../../shared/format';
import { PlannedSell, SellPlan } from './sell-plan';

/**
 * Raise Cash: the sells the amount takes, before the client commits to them. Each sell shows its
 * shares, what it should raise at the bid, and the gain or loss it would realise against average
 * cost; the totals show the cash raised and the cash balance after. The button puts the sells in
 * the trade plan tagged Raise Cash, replacing any Raise Cash trades already there.
 */
@Component({
  selector: 'app-raise-cash-preview',
  imports: [CommonModule],
  template: `
    <section class="panel" aria-labelledby="sells-title" data-testid="sell-preview">
      <h2 id="sells-title">Sells</h2>

      <table *ngIf="plan().sells.length; else none" class="sells">
        <caption class="sr-only">Sells to raise the cash, with what each raises and realises</caption>
        <thead>
          <tr>
            <th scope="col">Stock</th>
            <th scope="col" class="num">Shares</th>
            <th scope="col" class="num">Est. proceeds</th>
            <th scope="col" class="num">Gain/loss realised</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let sell of plan().sells; trackBy: bySymbol" [attr.data-sell]="sell.symbol">
            <th scope="row"><span class="verb">Sell</span> {{ sell.symbol }}</th>
            <td class="num">{{ sell.quantity }}</td>
            <td class="num">{{ money(sell.proceeds) }}</td>
            <td class="num" [ngClass]="tone(sell.realised)">{{ signed(sell.realised) }}</td>
          </tr>
        </tbody>
      </table>
      <ng-template #none>
        <p class="none" data-testid="no-sells">Nothing to sell yet. Raise the amount above your available cash.</p>
      </ng-template>

      <footer>
        <dl *ngIf="plan().sells.length" class="totals">
          <div><dt>Cash raised</dt><dd class="num" data-testid="cash-raised">{{ money(plan().raised) }}</dd></div>
          <div><dt>New cash balance</dt><dd class="num" data-testid="new-cash">{{ money(plan().cashAfter) }}</dd></div>
          <div *ngIf="plan().realised !== null">
            <dt>Gain/loss realised</dt>
            <dd class="num" data-testid="realised-total" [ngClass]="tone(plan().realised)">{{ signed(plan().realised) }}</dd>
          </div>
        </dl>
        <p *ngIf="plan().sells.length" class="estimate">Estimates at today’s bid prices. What you get depends on the price when each sell fills.</p>
        <button type="button" class="primary" data-testid="add-to-plan" [disabled]="!plan().sells.length || inPlan()"
                (click)="add.emit()">
          <ng-container *ngIf="inPlan(); else label">
            <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M3.5 8.5l3 3 6-7" /></svg> In your plan
          </ng-container>
          <ng-template #label>{{ hasPlanned() ? 'Update plan' : 'Add to plan' }}</ng-template>
        </button>
        <p *ngIf="hasPlanned() && !inPlan() && plan().sells.length" class="replaces" data-testid="replaces">
          Replaces the Raise Cash trades already in your plan.
        </p>
      </footer>
    </section>
  `,
  styles: [`
    :host { display: block; --subtle: #a1a1ae; }
    .panel {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding: 16px;
      border-radius: var(--radius-lg);
      border: 1px solid var(--border);
      background: color-mix(in srgb, var(--card) 70%, transparent);
    }
    h2 { font-size: 16px; font-weight: 600; color: var(--foreground); }
    .sells { width: 100%; border-collapse: collapse; font-size: 14px; color: var(--foreground); }
    .sells th, .sells td { padding: 7px 6px; text-align: left; border-bottom: 1px solid var(--border); }
    .sells thead th { font-size: 12px; font-weight: 600; color: var(--subtle); }
    .sells tbody th { font-weight: 600; }
    .sells .num { text-align: right; }
    .verb { font-weight: 400; color: var(--subtle); }
    .num { font-variant-numeric: tabular-nums; }
    .gain { color: var(--gain); }
    .loss { color: var(--loss); }
    .none, .estimate, .replaces { font-size: 13px; color: var(--subtle); }
    .estimate, .replaces { font-size: 12px; }
    .replaces { text-align: center; }
    footer { display: flex; flex-direction: column; gap: 10px; }
    .totals { display: grid; gap: 4px; margin: 0; }
    .totals div { display: flex; justify-content: space-between; gap: 12px; font-size: 14px; }
    .totals dt { color: var(--subtle); }
    .totals dd { margin: 0; color: var(--foreground); font-weight: 600; }
    .totals dd.gain { color: var(--gain); }
    .totals dd.loss { color: var(--loss); }
    .primary {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      padding: 10px 18px;
      border: 0;
      border-radius: 999px;
      background: var(--primary);
      color: var(--primary-foreground);
      font-size: 14px;
      font-weight: 600;
      cursor: pointer;
      transition: filter 0.15s ease, transform 0.15s ease;
    }
    .primary svg { fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
    .primary:hover:not(:disabled) { filter: brightness(1.08); }
    .primary:active:not(:disabled) { transform: scale(0.98); }
    .primary:disabled { background: var(--muted); color: var(--subtle); cursor: default; }
    @media (max-width: 640px) {
      .sells { font-size: 13px; }
      .sells th, .sells td { padding: 6px 4px; }
    }
    @media (prefers-reduced-motion: reduce) { .primary { transition: none; } }
  `]
})
export class RaiseCashPreviewComponent {
  readonly plan = input.required<SellPlan>();
  /** Whether the tray already holds Raise Cash trades for this account. */
  readonly hasPlanned = input(false);
  /** Whether those trades are exactly these. */
  readonly inPlan = input(false);
  readonly add = output<void>();

  money = formatMoney;
  signed = formatSignedMoney;

  tone(value: number | null): string {
    return value === null || value === 0 ? '' : value > 0 ? 'gain' : 'loss';
  }

  bySymbol(_index: number, sell: PlannedSell): string {
    return sell.symbol;
  }
}
