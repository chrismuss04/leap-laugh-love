import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ConcentrationMonth, ConcentrationReport, ConcentrationTier, ReportsService } from '../../../services/reports';
import { ReportPanelComponent } from '../../shared/report-panel';
import { AnalystReport } from './analyst-report';
import { compactMoney, count, percent } from './report-format';

/** The slices the report shows, in the order reporting-app returns them. */
const TIERS = [1, 10, 20];

/**
 * Analyst reports: how much of each month's traded volume comes from its biggest clients. The
 * three slices nest, so they share one bar: the widest (top 20%) underneath, the top 1% on top.
 */
@Component({
  selector: 'app-concentration-report',
  imports: [CommonModule, ReportPanelComponent],
  templateUrl: './concentration-report.html',
  styleUrl: './reports.css',
  styles: `
    .nested { min-width: 120px; }
    .nested .bar-fill { position: absolute; inset: 0 auto 0 0; }
    .tier-0 { background: var(--primary); }
    .tier-1 { background: color-mix(in srgb, var(--primary) 55%, var(--card)); }
    .tier-2 { background: color-mix(in srgb, var(--primary) 25%, var(--card)); }
  `
})
export class ConcentrationReportComponent extends AnalystReport<ConcentrationReport> {
  private readonly reports = inject(ReportsService);

  readonly tiers = TIERS;
  /** Drawn widest first, so each narrower slice sits on top of the one that contains it. */
  readonly widestFirst = [...TIERS].reverse();
  readonly count = count;
  readonly percent = percent;
  readonly compactMoney = compactMoney;

  tier(month: ConcentrationMonth, topPct: number): ConcentrationTier | undefined {
    return month.tiers.find(t => t.topPct === topPct);
  }

  protected fetch() {
    return this.reports.getConcentration();
  }

  protected isEmpty(report: ConcentrationReport): boolean {
    return report.months.every(m => m.tradingClients === 0);
  }
}
