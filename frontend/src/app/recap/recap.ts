import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { RecapApi, RecapResponse } from '../services/recap';
import { RecapSeenService } from '../services/recap-seen';
import { IntroCardComponent } from './cards/intro-card';
import { MoneyMovedCardComponent } from './cards/money-moved-card';
import { TheTopsCardComponent } from './cards/the-tops-card';
import { TopGainerCardComponent } from './cards/top-gainer-card';
import { TopTickersCardComponent } from './cards/top-tickers-card';
import { VsSp500CardComponent } from './cards/vs-sp500-card';
import { loadRecapFonts } from './recap-fonts';
import { StoryCardDirective, StoryViewerComponent } from './story-viewer/story-viewer';

const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September',
  'October', 'November', 'December'];

/**
 * The client's monthly recap: last month's trading as a story of six cards. Opening it clears the
 * "new" marks on the Recap tab and menu item for that month.
 */
@Component({
  selector: 'app-recap',
  imports: [StoryViewerComponent, StoryCardDirective, IntroCardComponent, MoneyMovedCardComponent,
    TopTickersCardComponent, TopGainerCardComponent, VsSp500CardComponent, TheTopsCardComponent],
  templateUrl: './recap.html',
  styleUrl: './recap.css'
})
export class RecapComponent implements OnInit {
  private readonly api = inject(RecapApi);
  private readonly seen = inject(RecapSeenService);

  readonly response = signal<RecapResponse | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly recap = computed(() => this.response()?.recap ?? null);
  /** "September". */
  readonly monthName = computed(() => {
    const month = this.response()?.month;
    return month ? MONTH_NAMES[Number(month.slice(5, 7)) - 1] : '';
  });

  ngOnInit(): void {
    loadRecapFonts();
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.latest().subscribe({
      next: response => {
        this.response.set(response);
        this.loading.set(false);
        this.seen.markSeen(response.month);
      },
      error: (err: HttpErrorResponse) => {
        // reporting-app explains a 503 (prices unavailable) itself; anything else gets a generic line.
        this.error.set(err.error?.message ?? "Couldn't load your recap. Please try again.");
        this.loading.set(false);
      }
    });
  }
}
