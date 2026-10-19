import { Component, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { formatMoney } from '../../shared/format';
import { Allocation, DonutSlice } from './allocation';
import { TargetAllocation } from './target-allocation';
import { WHOLE, formatTarget, onTarget, parseTarget, targetChange } from './targets';
import { SliceTrade, TradePreview } from './trade-preview';

interface TargetRow {
  slice: DonutSlice;
  target: number;
  /** The target as typed: "25", "33.3". */
  text: string;
  /** Dollars to buy (positive) or sell (negative) to reach the target. */
  change: number;
  /** The whole-share trade the preview plans for this holding, when trades are shown. */
  trade: SliceTrade | null;
  locked: boolean;
}

/** Under this many dollars a change is shown as none: it's rounding, not a trade. */
const NO_CHANGE = 0.005;

/**
 * Rebalance Donut: each slice's current and target share and the dollars it takes to get there,
 * under the donut. Targets can be typed here (the donut follows), locked so other changes leave
 * them be, reset to the current allocation, or spread evenly over the holdings.
 */
@Component({
  selector: 'app-target-table',
  imports: [CommonModule],
  template: `
    <div class="head">
      <div>
        <h2 id="targets-title">Targets</h2>
        <p>Type a target, or drag the handles on the outer ring.</p>
      </div>
      <div class="shortcuts">
        <button type="button" class="ghost" (click)="targets().equalWeight()" [disabled]="!canEqualWeight()"
                data-testid="equal-weight">
          <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M2 5h12M2 11h12" /></svg>
          Equal weight
        </button>
        <button type="button" class="ghost" (click)="targets().reset()" [disabled]="!targets().edited()"
                data-testid="reset-targets">
          <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M3 8a5 5 0 1 0 1.5-3.6M3 2v3h3" /></svg>
          Reset
        </button>
      </div>
    </div>

    <div class="scroll">
      <table aria-labelledby="targets-title">
        <thead>
          <tr>
            <th scope="col" class="lock-col"><span class="sr-only">Lock</span></th>
            <th scope="col">Holding</th>
            <th scope="col" class="num current-col">Current</th>
            <th scope="col" class="num">Target</th>
            <th scope="col" class="num">Change</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let row of rows(); trackBy: trackRow" [class.locked]="row.locked" [attr.data-key]="row.slice.key">
            <td class="lock-col">
              <button type="button" class="lock" [attr.aria-pressed]="row.locked" (click)="targets().toggleLock(row.slice.key)"
                      [attr.aria-label]="(row.locked ? 'Unlock ' : 'Lock ') + row.slice.label + ' target'"
                      [attr.title]="row.locked ? 'Locked: other changes leave this target alone' : 'Lock this target'">
                <svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true">
                  <rect x="3" y="7" width="10" height="7" rx="1.5" />
                  <path [attr.d]="row.locked ? 'M5.5 7V5a2.5 2.5 0 0 1 5 0v2' : 'M5.5 7V5a2.5 2.5 0 0 1 4.9-.7'" />
                </svg>
              </button>
            </td>
            <th scope="row">
              <span class="who">
                <span class="swatch" [style.background]="row.slice.color" aria-hidden="true"></span>
                <span class="symbol">{{ row.slice.label }}</span>
                <span *ngIf="row.slice.added" class="new-tag">New</span>
                <button *ngIf="row.slice.added" type="button" class="remove" (click)="targets().remove(row.slice.key)"
                        [attr.aria-label]="'Remove ' + row.slice.label + ' from your donut'" title="Remove from your donut"
                        [attr.data-remove]="row.slice.key">
                  <svg viewBox="0 0 16 16" width="12" height="12" aria-hidden="true"><path d="M4 4l8 8M12 4l-8 8" /></svg>
                </button>
                <span class="now num">{{ row.slice.added ? 'Not owned yet' : current(row) + ' now' }}</span>
              </span>
            </th>
            <td class="num current-col">{{ row.slice.added ? '—' : current(row) }}</td>
            <td class="num">
              <label class="field" [class.zero]="row.target === 0">
                <span class="sr-only">{{ row.slice.label }} target percent</span>
                <input #field type="text" inputmode="decimal" autocomplete="off" class="num" [value]="row.text"
                       [disabled]="row.locked || !canType()" [attr.data-target-input]="row.slice.key"
                       (change)="commit(row, field)" (keydown.enter)="field.blur()"
                       (keydown.escape)="field.value = row.text; field.blur()" (focus)="field.select()" />
                <span class="suffix" aria-hidden="true">%</span>
              </label>
            </td>
            <td class="num change" [attr.data-change]="row.slice.key">
              <ng-container *ngIf="abs(row.change) >= noChange; else none">
                <span class="amount" [class.buy]="row.change > 0" [class.sell]="row.change < 0">
                  {{ preview() ? '≈ ' : '' }}{{ row.change > 0 ? '+' : '−' }}{{ money(abs(row.change)) }}
                </span>
                <span class="verb">{{ verb(row) }}</span>
              </ng-container>
              <ng-template #none><span class="verb">No change</span></ng-template>
            </td>
          </tr>
        </tbody>
        <tfoot>
          <tr>
            <td class="lock-col"></td>
            <th scope="row">Total</th>
            <td class="num current-col">100%</td>
            <td class="num" data-testid="target-total">{{ totalText() }}</td>
            <td class="num change">
              <span class="verb summary" data-testid="trade-summary">
                <span *ngFor="let part of summary()">{{ part }}</span>
              </span>
            </td>
          </tr>
        </tfoot>
      </table>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      container-type: inline-size;
      --subtle: #a1a1ae;
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 12px;
    }
    h2 { font-size: 16px; font-weight: 600; color: var(--foreground); }
    .head p { margin-top: 2px; font-size: 13px; color: var(--subtle); }
    .shortcuts { display: flex; gap: 8px; }
    .ghost {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 7px 14px;
      border-radius: 999px;
      border: 1px solid var(--border);
      background: transparent;
      color: var(--foreground);
      font-size: 13px;
      font-weight: 600;
      transition: background 0.15s ease, border-color 0.15s ease;
    }
    .ghost svg { fill: none; stroke: currentColor; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
    .ghost:hover:not(:disabled) { background: var(--surface-hover); border-color: color-mix(in srgb, var(--primary) 40%, var(--border)); }
    .ghost:disabled { color: var(--muted-foreground); cursor: default; }
    .scroll { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; font-size: 14px; }
    th, td { padding: 8px 10px; text-align: left; vertical-align: middle; }
    thead th {
      padding-top: 0;
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: var(--subtle);
      border-bottom: 1px solid var(--border);
    }
    .num { text-align: right; font-variant-numeric: tabular-nums; }
    tbody tr { border-bottom: 1px solid color-mix(in srgb, var(--border) 60%, transparent); transition: background 0.15s ease; }
    tbody tr:hover { background: color-mix(in srgb, var(--surface-hover) 70%, transparent); }
    tbody th { font-weight: 400; }
    .lock-col { width: 40px; padding-right: 0; }
    .lock {
      display: inline-grid;
      place-items: center;
      width: 30px;
      height: 30px;
      border-radius: 8px;
      background: transparent;
      color: var(--muted-foreground);
      transition: color 0.15s ease, background 0.15s ease;
    }
    .lock svg { fill: none; stroke: currentColor; stroke-width: 1.6; stroke-linecap: round; }
    .lock:hover { color: var(--foreground); background: var(--surface-hover); }
    .lock[aria-pressed='true'] { color: var(--primary); background: color-mix(in srgb, var(--primary) 12%, transparent); }
    .who { display: flex; align-items: center; gap: 10px; min-width: 0; }
    .swatch { width: 10px; height: 10px; flex: none; border-radius: 3px; }
    .symbol { font-weight: 600; color: var(--foreground); }
    .now { display: none; font-size: 12px; color: var(--subtle); }
    .new-tag {
      padding: 1px 7px;
      border-radius: 999px;
      background: color-mix(in srgb, var(--primary) 16%, transparent);
      color: var(--primary);
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }
    .remove {
      display: inline-grid;
      place-items: center;
      width: 22px;
      height: 22px;
      border-radius: 6px;
      background: transparent;
      color: var(--subtle);
      transition: background 0.15s ease, color 0.15s ease;
    }
    .remove svg { fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; }
    .remove:hover { background: var(--loss-muted); color: var(--loss); }
    .current-col { color: var(--subtle); }
    .field {
      display: inline-flex;
      align-items: center;
      width: 92px;
      padding: 0 10px;
      border-radius: 8px;
      border: 1px solid var(--border);
      background: var(--background);
      transition: border-color 0.15s ease, box-shadow 0.15s ease;
    }
    .field:focus-within { border-color: var(--primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--primary) 20%, transparent); }
    .field.zero { border-style: dashed; }
    input {
      width: 100%;
      min-width: 0;
      padding: 7px 0;
      border: none;
      outline: none;
      background: transparent;
      color: var(--foreground);
      font-size: 14px;
      text-align: right;
    }
    input:disabled { color: var(--subtle); }
    .suffix { margin-left: 2px; color: var(--subtle); font-size: 13px; }
    .locked .field { border-color: color-mix(in srgb, var(--primary) 30%, var(--border)); background: transparent; }
    .change { white-space: nowrap; }
    .amount { display: block; font-weight: 600; color: var(--foreground); }
    .amount.buy { color: var(--gain); }
    .amount.sell { color: var(--loss); }
    .verb { display: block; font-size: 12px; color: var(--subtle); }
    tfoot th, tfoot td { padding-top: 12px; font-weight: 600; color: var(--foreground); }
    tfoot .verb { font-weight: 400; }
    .summary { white-space: normal; }
    .summary span + span::before { content: ' · '; }
    /* On a phone the current share moves under the symbol, so the target and change keep their room. */
    @container (max-width: 520px) {
      .current-col { display: none; }
      .who { display: grid; grid-template-columns: 10px repeat(3, auto); justify-content: start; align-items: center; column-gap: 6px; row-gap: 0; }
      .now { display: block; grid-column: 2 / -1; justify-self: start; white-space: nowrap; }
      th, td { padding: 8px 4px; }
      .lock-col { width: 32px; }
      .lock { width: 28px; height: 28px; }
      .field { width: 66px; padding: 0 8px; }
      .amount { font-size: 13px; }
      .summary span { display: block; }
      .summary span + span::before { content: none; }
      .head { align-items: flex-start; }
    }
    @media (prefers-reduced-motion: reduce) {
      .ghost, .lock, .field, tbody tr { transition: none; }
    }
  `]
})
export class TargetTableComponent {
  readonly allocation = input.required<Allocation>();
  readonly targets = input.required<TargetAllocation>();
  /**
   * The planned trades. Given, the Change column shows each holding's whole-share trade and its
   * estimated value, so it always agrees with the trades list; without, the exact dollar gap.
   */
  readonly preview = input<TradePreview | null>(null);

  readonly rows = computed<TargetRow[]>(() => {
    const plan = this.targets();
    const preview = this.preview();
    const { total, slices } = this.allocation();
    const cash = slices.find(s => s.kind === 'cash')?.value ?? 0;
    plan.targets();
    return slices.map(slice => {
      const target = plan.targetOf(slice.key) ?? 0;
      const trade = preview?.bySymbol[slice.key] ?? null;
      let change = targetChange(target, slice.share, total);
      if (preview) {
        change = slice.kind === 'cash' ? preview.cashAfter - cash : trade ? (trade.side === 'BUY' ? trade.value : -trade.value) : 0;
      }
      return {
        slice,
        target,
        text: target % 10 === 0 ? String(target / 10) : (target / 10).toFixed(1),
        change,
        trade,
        locked: plan.isLocked(slice.key)
      };
    });
  });

  /** Typing needs another unlocked slice to take up the difference. */
  readonly canType = computed(() => this.rows().filter(row => !row.locked).length > 1);
  readonly canEqualWeight = computed(() => this.rows().some(row => row.slice.kind === 'holding' && !row.locked));

  readonly totalText = computed(() => formatTarget(this.rows().reduce((sum, row) => sum + row.target, 0)));

  /** ["Buy $1,200.00", "Sell $900.00"]: what reaching the targets trades, cash aside. */
  readonly summary = computed(() => {
    const preview = this.preview();
    if (preview) {
      const parts = [preview.buyTotal ? `Buy ≈ ${formatMoney(preview.buyTotal)}` : '',
        preview.sellTotal ? `Sell ≈ ${formatMoney(preview.sellTotal)}` : ''].filter(Boolean);
      return parts.length ? parts : [preview.balanced ? 'Already balanced' : 'No trades'];
    }
    const holdings = this.rows().filter(row => row.slice.kind === 'holding');
    const buys = holdings.filter(row => row.change >= NO_CHANGE).reduce((sum, row) => sum + row.change, 0);
    const sells = holdings.filter(row => row.change <= -NO_CHANGE).reduce((sum, row) => sum - row.change, 0);
    const parts = [buys ? `Buy ${formatMoney(buys)}` : '', sells ? `Sell ${formatMoney(sells)}` : ''].filter(Boolean);
    return parts.length ? parts : ['On target'];
  });

  readonly noChange = NO_CHANGE;
  money = formatMoney;
  abs = Math.abs;

  /**
   * The current share to a tenth, to line up with the target beside it. A target that's just the
   * share rounded (so 100% adds up) shows as the same figure, rather than a tenth apart.
   */
  current(row: TargetRow): string {
    return formatTarget(onTarget(row.target, row.slice.share) ? row.target : Math.round(row.slice.share * WHOLE));
  }

  verb(row: TargetRow): string {
    if (row.slice.kind === 'cash') {
      return row.change > 0 ? 'More cash' : 'Less cash';
    }
    if (row.trade) {
      const all = row.trade.side === 'SELL' && row.trade.quantity === row.slice.shares ? ' all' : '';
      return `${row.trade.side === 'BUY' ? '↑ Buy' : '↓ Sell' + all} ${row.trade.quantity}`;
    }
    if (row.target === 0) {
      return 'Sell all';
    }
    return row.change > 0 ? 'Buy' : 'Sell';
  }

  /** A typed target: applied if it's a number, else the field goes back to the target it had. */
  commit(row: TargetRow, field: HTMLInputElement): void {
    const tenths = parseTarget(field.value);
    if (tenths !== null && tenths !== row.target) {
      this.targets().type(row.slice.key, tenths);
    }
    // The applied target may differ from what was typed (capped, or rounded to a tenth).
    const applied = this.targets().targetOf(row.slice.key) ?? row.target;
    field.value = applied % 10 === 0 ? String(applied / 10) : (applied / 10).toFixed(1);
  }

  trackRow = (_: number, row: TargetRow) => row.slice.key;
}
