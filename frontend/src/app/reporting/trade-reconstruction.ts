import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { TradeOpsService, TradeSearch, TradeSummary, TradeTimeline } from '../services/trade-ops';

@Component({
    selector: 'app-trade-reconstruction',
    imports: [DatePipe, FormsModule],
    templateUrl: './trade-reconstruction.html',
    styleUrl: './trade-reconstruction.css'
})
export class TradeReconstructionComponent {
  criteria: TradeSearch = {};
  results = signal<TradeSummary[] | null>(null);
  timeline = signal<TradeTimeline | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);

  private tradeOps = inject(TradeOpsService);

  search() {
    if (!Object.values(this.criteria).some(value => value?.trim())) {
      this.error.set('Fill in at least one search field');
      return;
    }
    this.load(this.tradeOps.search(this.criteria), results => {
      this.results.set(results);
      this.timeline.set(null);
    });
  }

  open(orderId: string) {
    this.load(this.tradeOps.timeline(orderId), timeline => this.timeline.set(timeline));
  }

  download(orderId: string) {
    this.load(this.tradeOps.timelineCsv(orderId), csv => {
      const link = document.createElement('a');
      link.href = URL.createObjectURL(csv);
      link.download = `trade-${orderId}.csv`;
      link.click();
      URL.revokeObjectURL(link.href);
    });
  }

  private load<T>(request: Observable<T>, next: (value: T) => void) {
    this.loading.set(true);
    this.error.set(null);
    request.subscribe({
      next: value => {
        next(value);
        this.loading.set(false);
      },
      error: err => {
        this.error.set(err.error?.message || 'The request failed');
        this.loading.set(false);
      }
    });
  }
}
