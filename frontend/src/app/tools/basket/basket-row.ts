import { Component, computed, input, output } from '@angular/core';
import { direction, formatMoney, formatSignedPercent } from '../../shared/format';
import { BasketItem } from './basket';

/**
 * Basket Builder: one stock in the basket, at its live price with today's change. Anything placed
 * inside the row sits between the change and the remove button, for columns a later story adds.
 */
@Component({
  selector: 'app-basket-row',
  template: `
    <span class="stock">
      <span class="symbol">{{ item().symbol }}</span>
      <span class="name">{{ item().name }}</span>
    </span>
    <span class="price num" data-testid="basket-price">{{ money(price()) }}</span>
    <span class="change num" [class.text-gain]="trend() === 'gain'" [class.text-loss]="trend() === 'loss'"
          [class.text-flat]="trend() === 'flat'" data-testid="basket-change">{{ percent(changePercent()) }}</span>
    <ng-content></ng-content>
    <button type="button" class="remove" data-testid="basket-remove" (click)="remove.emit()"
            [attr.aria-label]="'Remove ' + item().symbol + ' from basket'">
      <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M4 4l8 8M12 4l-8 8" /></svg>
    </button>
  `,
  styles: [`
    :host {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto 72px auto 28px;
      align-items: center;
      gap: 12px;
      padding: 10px 12px;
      border-radius: var(--radius-md);
      background: var(--surface);
      border: 1px solid var(--border);
    }
    .stock { display: flex; flex-direction: column; min-width: 0; }
    .symbol { font-weight: 600; color: var(--foreground); }
    .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--muted-foreground); }
    .price { color: var(--foreground); }
    .change { text-align: right; font-size: 13px; }
    .text-flat { color: var(--muted-foreground); }
    .remove { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 8px; background: transparent; color: var(--muted-foreground); }
    .remove:hover, .remove:focus-visible { background: var(--surface-hover); color: var(--foreground); }
    .remove svg { fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; }
    /* Narrow: price over change, so the name keeps its room. */
    @media (max-width: 640px) {
      :host {
        grid-template-columns: minmax(0, 1fr) auto 28px;
        grid-template-areas: 'stock price remove' 'stock change remove';
        row-gap: 2px;
      }
      .stock { grid-area: stock; }
      .price { grid-area: price; text-align: right; }
      .change { grid-area: change; }
      .remove { grid-area: remove; }
    }
  `]
})
export class BasketRowComponent {
  readonly item = input.required<BasketItem>();
  readonly price = input<number | null>(null);
  /** Today's change in percent, or null when there's no baseline to measure it from. */
  readonly changePercent = input<number | null>(null);
  readonly remove = output<void>();

  readonly trend = computed(() => direction(this.changePercent()));
  readonly money = formatMoney;
  readonly percent = formatSignedPercent;
}
