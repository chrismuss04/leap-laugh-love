import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { HoldingView } from '../models';
import { SparklineComponent } from '../sparkline/sparkline';
import { FlashDirective } from '../../shared/flash.directive';
import { direction, formatMoney, formatSignedMoney, formatSignedPercent } from '../../shared/format';

/** The client's holdings. Selecting a row loads that symbol into the trade panel. */
@Component({
  selector: 'app-positions-list',
  standalone: true,
  imports: [CommonModule, RouterLink, SparklineComponent, FlashDirective],
  templateUrl: './positions-list.html',
  styleUrl: './positions-list.css'
})
export class PositionsListComponent {
  @Input() holdings: HoldingView[] = [];
  @Input() loading = false;
  @Input() selected: string | null = null;
  @Output() select = new EventEmitter<string>();
  @Output() trade = new EventEmitter<{ symbol: string; side: 'BUY' | 'SELL' }>();

  money = formatMoney;
  signedMoney = formatSignedMoney;
  signedPercent = formatSignedPercent;
  dir = direction;

  trackBySymbol(_: number, holding: HoldingView): string {
    return holding.symbol;
  }
}
