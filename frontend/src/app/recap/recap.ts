import { Component, DOCUMENT, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { RecapResponse, RecapService } from '../services/recap';
import { CARD_LABELS, RECAP_CARDS, isEmptyMonth, monthName } from './recap-format';
import { RecapIntroCardComponent } from './cards/intro-card';
import { RecapMoneyCardComponent } from './cards/money-card';
import { RecapTickersCardComponent } from './cards/tickers-card';
import { RecapGainerCardComponent } from './cards/gainer-card';
import { RecapBenchCardComponent } from './cards/bench-card';
import { RecapTopsCardComponent } from './cards/tops-card';

/** How long each card shows before the story moves on; recap.css's fill animation runs this long. */
export const CARD_DURATION_MS = 7000;

// The cards' fonts. Loaded when the recap opens rather than in styles.css: the build inlines
// global font CSS, and these would add ~110 kB to every page's initial load.
export const RECAP_FONTS_URL =
  'https://fonts.googleapis.com/css2?family=Dela+Gothic+One&family=Inter:wght@400;600;800&display=swap';

function loadRecapFonts(doc: Document): void {
  if (!doc.head.querySelector(`link[href="${RECAP_FONTS_URL}"]`)) {
    const link = doc.createElement('link');
    link.rel = 'stylesheet';
    link.href = RECAP_FONTS_URL;
    doc.head.appendChild(link);
  }
}

/**
 * The client's recap of last month, played as a story: one card at a time, moving on by itself
 * unless paused. Click the right of a card (or press the right arrow) to go on, the left to go
 * back, space to pause. The cards animate whatever the browser's reduced-motion setting.
 */
@Component({
    selector: 'app-recap',
    imports: [RouterLink, RecapIntroCardComponent, RecapMoneyCardComponent, RecapTickersCardComponent,
      RecapGainerCardComponent, RecapBenchCardComponent, RecapTopsCardComponent],
    templateUrl: './recap.html',
    styleUrl: './recap.css'
})
export class RecapComponent implements OnInit {
  private readonly recapService = inject(RecapService);
  private readonly document = inject(DOCUMENT);

  readonly response = signal<RecapResponse | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly index = signal(0);
  readonly paused = signal(false);

  readonly cards = computed(() => {
    const response = this.response();
    return response && !isEmptyMonth(response.recap) ? RECAP_CARDS : [];
  });
  readonly card = computed(() => this.cards()[this.index()]);
  readonly isLast = computed(() => this.index() === this.cards().length - 1);
  readonly stageLabel = computed(() =>
    `Recap card ${this.index() + 1} of ${this.cards().length}: ${CARD_LABELS[this.card()]}`);
  readonly monthName = monthName;
  readonly duration = `${CARD_DURATION_MS}ms`;

  ngOnInit(): void {
    loadRecapFonts(this.document);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.recapService.latest().subscribe({
      next: response => {
        this.response.set(response);
        this.index.set(0);
        this.loading.set(false);
        this.recapService.markSeen(response.month);
      },
      error: (err: HttpErrorResponse) => {
        // 503: market data was down while the recap was being worked out; it's worth a retry.
        this.error.set(err.error?.message || 'Your recap couldn’t be loaded. Try again shortly.');
        this.loading.set(false);
      }
    });
  }

  next(): void {
    if (this.isLast()) {
      this.paused.set(true);
    } else {
      this.index.update(i => i + 1);
    }
  }

  prev(): void {
    this.index.update(i => Math.max(0, i - 1));
  }

  /** Play from the last card starts the story again. */
  togglePause(): void {
    if (this.paused() && this.isLast()) {
      this.index.set(0);
    }
    this.paused.update(paused => !paused);
  }

  /** The left third of the card goes back; the rest goes on. */
  onStageClick(event: MouseEvent): void {
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
    if (event.clientX - rect.left < rect.width / 3) {
      this.prev();
    } else {
      this.next();
    }
  }

  onStageKey(event: KeyboardEvent): void {
    switch (event.key) {
      case 'ArrowRight':
        event.preventDefault();
        this.next();
        break;
      case 'ArrowLeft':
        event.preventDefault();
        this.prev();
        break;
      case ' ':
        event.preventDefault();
        this.togglePause();
        break;
      default:
        // Any other key keeps its usual behaviour, such as Tab moving focus.
        break;
    }
  }

  /** The current card's progress bar has filled: time to move on. */
  onSegmentEnd(segment: number): void {
    if (segment === this.index() && !this.paused()) {
      this.next();
    }
  }
}
