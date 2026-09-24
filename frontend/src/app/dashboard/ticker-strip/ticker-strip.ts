import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MarketIndex } from '../models';
import { FlashDirective } from '../../shared/flash.directive';
import { direction, formatNumber, formatSignedPercent, percentChange } from '../../shared/format';

/** Headline market indices with their live level and change since the previous close. */
@Component({
    selector: 'app-ticker-strip',
    imports: [CommonModule, FlashDirective],
    template: `
    <section class="strip" aria-label="Market indices">
      <div class="item" *ngFor="let index of indices; trackBy: trackBySymbol">
        <span class="label">{{ index.label }}</span>
        <span class="values">
          <span class="price num" [appFlash]="index.price">
            <ng-container *ngIf="index.price !== null; else loading">{{ format(index.price) }}</ng-container>
            <ng-template #loading><span class="skeleton placeholder"></span></ng-template>
          </span>
          <span class="change num" [ngClass]="'text-' + dir(index)" *ngIf="index.price !== null">
            {{ arrow(index) }} {{ percent(index) }}
          </span>
        </span>
      </div>
    </section>
  `,
    styles: [`
    .strip {
      display: grid;
      grid-template-columns: repeat(6, minmax(0, 1fr));
      border-bottom: 1px solid var(--border);
    }
    .item {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 12px 16px;
      border-right: 1px solid var(--border);
      min-width: 0;
    }
    .item:last-child { border-right: none; }
    .label {
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: var(--muted-foreground);
    }
    .values { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
    .price { font-size: 14px; font-weight: 600; border-radius: 4px; }
    .change { font-size: 12px; }
    .text-flat { color: var(--muted-foreground); }
    .placeholder { display: inline-block; width: 72px; height: 14px; }
    @media (max-width: 1000px) {
      .strip { display: flex; overflow-x: auto; scrollbar-width: none; }
      .item { flex: 0 0 auto; min-width: 150px; }
    }
  `]
})
export class TickerStripComponent {
  @Input() indices: MarketIndex[] = [];

  format = formatNumber;

  dir(index: MarketIndex): string {
    return direction(this.change(index));
  }

  arrow(index: MarketIndex): string {
    const change = this.change(index);
    return change == null || change === 0 ? '' : change > 0 ? '▲' : '▼';
  }

  percent(index: MarketIndex): string {
    return formatSignedPercent(percentChange(index.price, index.previousClose));
  }

  trackBySymbol(_: number, index: MarketIndex): string {
    return index.symbol;
  }

  private change(index: MarketIndex): number | null {
    return index.price != null && index.previousClose != null ? index.price - index.previousClose : null;
  }
}
