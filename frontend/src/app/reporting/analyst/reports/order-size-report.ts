import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { OrderSizeBand, OrderSizeMonth, OrderSizeReport, ReportsService } from '../../../services/reports';
import { formatMoney } from '../../../shared/format';
import { ReportPanelComponent } from '../../shared/report-panel';
import { AnalystReport } from './analyst-report';
import { barWidth, compactMoney, count } from './report-format';

/** Analyst reports: how each month's filled orders spread across value bands, with the typical order's value. */
@Component({
  selector: 'app-order-size-report',
  imports: [CommonModule, ReportPanelComponent],
  templateUrl: './order-size-report.html',
  styleUrl: './reports.css'
})
export class OrderSizeReportComponent extends AnalystReport<OrderSizeReport> {
  private readonly reports = inject(ReportsService);

  /** The bands, smallest first, as every month lists them. */
  readonly bands = computed(() => this.report()?.months[0]?.bands ?? []);

  readonly count = count;
  readonly formatMoney = formatMoney;

  /** '< $1K', '$1K–$5K', '$25K+' */
  bandLabel(band: OrderSizeBand): string {
    if (band.from == null) { return `< ${compactMoney(band.to)}`; }
    if (band.to == null) { return `${compactMoney(band.from)}+`; }
    return `${compactMoney(band.from)}–${compactMoney(band.to)}`;
  }

  /** Each band's share of the month's filled orders, for the stacked bar. */
  share(month: OrderSizeMonth, band: OrderSizeBand): number {
    return barWidth(band.orders, month.filledOrders);
  }

  protected fetch() {
    return this.reports.getOrderSizes();
  }

  protected isEmpty(report: OrderSizeReport): boolean {
    return report.months.every(m => m.filledOrders === 0);
  }
}
