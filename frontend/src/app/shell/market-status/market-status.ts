import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MarketStatusService } from '../../services/market-status';
import {
  MarketSession, dayNote, easternDateTime, sessionLabel, sessionName, sessionShortLabel, sessionTone, timelinePosition,
  timelineSegments
} from '../../shared/market-session';

/**
 * The header's market status: a pill with the session and what happens next ("Market open ·
 * Closes in 2h 14m"), opening a card with today's sessions on a 4:00-20:00 ET timeline.
 *
 * Screen readers hear the session when it changes, not the countdown every second.
 */
@Component({
  selector: 'app-market-status',
  imports: [CommonModule],
  templateUrl: './market-status.html',
  styleUrl: './market-status.css'
})
export class MarketStatusComponent implements OnInit, OnDestroy {
  readonly market = inject(MarketStatusService);
  readonly open = signal(false);

  readonly label = sessionLabel;
  readonly name = sessionName;
  readonly shortLabel = sessionShortLabel;
  readonly tone = sessionTone;

  readonly segments = computed(() => {
    const status = this.market.status();
    return status ? timelineSegments(status.today) : [];
  });

  /** Where now sits on the timeline, 0-100, or null outside it. */
  readonly nowPosition = computed(() => {
    const status = this.market.status();
    return status ? timelinePosition(status.today, this.market.now()) : null;
  });

  readonly note = computed(() => {
    const status = this.market.status();
    return status ? dayNote(status) : null;
  });

  /** "Mon, Oct 19 · 9:30 AM ET", shown while the regular session isn't under way. */
  readonly nextOpen = computed(() => {
    const status = this.market.status();
    if (!status || status.session === 'REGULAR' || !status.nextRegularOpen) {
      return null;
    }
    return easternDateTime(status.nextRegularOpen);
  });

  ngOnInit(): void {
    this.market.start();
  }

  ngOnDestroy(): void {
    this.market.stop();
  }

  toggle(): void {
    this.open.update(open => !open);
  }

  close(): void {
    this.open.set(false);
  }

  isCurrent(session: MarketSession): boolean {
    return this.market.session() === session;
  }
}
