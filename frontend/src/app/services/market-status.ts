import { Injectable, OnDestroy, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { MarketStatus, nextChangePhrase } from '../shared/market-session';

/**
 * The market's session - pre-market, regular, after-hours or closed - kept current for the whole
 * app. Fetched from market-data-app, then fetched again the moment the session changes (with a
 * slow safety net in case that moment is missed, e.g. the laptop slept). Between fetches a 1-second
 * clock drives the countdowns; it only runs while the page is visible.
 *
 * Countdowns use the server's clock: the browser's may be minutes off, and "Opens in 2m" must
 * agree with when order-app actually opens the market.
 *
 * The app is zoneless, so every timer writes a signal: that is what schedules a refresh.
 */
@Injectable({ providedIn: 'root' })
export class MarketStatusService implements OnDestroy {
  /** Same-origin path, proxied to market-data-app - see proxy.conf.js. */
  private static readonly URL = '/api/marketdata/market/status';
  /** Fetch again at least this often, in case a transition was missed. */
  static readonly REFRESH_MS = 5 * 60_000;
  /** After a failed fetch, try again this soon. */
  static readonly RETRY_MS = 30_000;
  /** Fetch this long after a transition, so the server is past it too. */
  private static readonly TRANSITION_GRACE_MS = 1_000;

  private readonly http = inject(HttpClient);

  /** The latest status, or null until the first answer. */
  readonly status = signal<MarketStatus | null>(null);
  /** Now, by the server's clock, ticking once a second while the page is visible. */
  readonly now = signal(Date.now());

  readonly session = computed(() => this.status()?.session ?? null);
  /** "Closes in 2h 14m", "Opens Mon 9:30 AM ET", or null. */
  readonly nextChange = computed(() => {
    const status = this.status();
    return status ? nextChangePhrase(status, this.now()) : null;
  });

  /** Server clock minus browser clock, from the last answer. */
  private skewMs = 0;
  private users = 0;
  private request?: Subscription;
  private fetchTimer?: ReturnType<typeof setTimeout>;
  private tickTimer?: ReturnType<typeof setInterval>;
  private readonly onVisibility = () => this.visibilityChanged();

  /** Starts keeping the status current. Each start needs a matching stop. */
  start(): void {
    this.users++;
    if (this.users > 1) {
      return;
    }
    document.addEventListener('visibilitychange', this.onVisibility);
    this.fetch();
    this.startTicking();
  }

  /** Stops keeping it current once the last user stops. */
  stop(): void {
    if (this.users === 0) {
      return;
    }
    this.users--;
    if (this.users > 0) {
      return;
    }
    document.removeEventListener('visibilitychange', this.onVisibility);
    this.request?.unsubscribe();
    clearTimeout(this.fetchTimer);
    this.stopTicking();
  }

  ngOnDestroy(): void {
    this.users = 1;
    this.stop();
  }

  /** Fetches the status now, and schedules the next fetch. */
  fetch(): void {
    this.request?.unsubscribe();
    clearTimeout(this.fetchTimer);
    this.request = this.http.get<MarketStatus>(MarketStatusService.URL).subscribe({
      next: status => {
        if (!status) {
          this.schedule(MarketStatusService.RETRY_MS);
          return;
        }
        this.skewMs = Date.parse(status.now) - Date.now();
        this.status.set(status);
        this.now.set(this.serverNow());
        this.schedule(this.delayAfter(status));
      },
      // Keep showing what we had; the header just doesn't change until the next answer.
      error: () => this.schedule(MarketStatusService.RETRY_MS)
    });
  }

  private delayAfter(status: MarketStatus): number {
    if (!status.nextTransitionAt) {
      return MarketStatusService.REFRESH_MS;
    }
    const untilTransition = Date.parse(status.nextTransitionAt) - this.serverNow() + MarketStatusService.TRANSITION_GRACE_MS;
    return Math.max(MarketStatusService.TRANSITION_GRACE_MS, Math.min(untilTransition, MarketStatusService.REFRESH_MS));
  }

  private schedule(delayMs: number): void {
    if (this.users === 0) {
      return;
    }
    clearTimeout(this.fetchTimer);
    this.fetchTimer = setTimeout(() => this.fetch(), delayMs);
  }

  private serverNow(): number {
    return Date.now() + this.skewMs;
  }

  private startTicking(): void {
    if (this.tickTimer || document.visibilityState === 'hidden') {
      return;
    }
    this.now.set(this.serverNow());
    this.tickTimer = setInterval(() => this.now.set(this.serverNow()), 1_000);
  }

  private stopTicking(): void {
    clearInterval(this.tickTimer);
    this.tickTimer = undefined;
  }

  private visibilityChanged(): void {
    if (document.visibilityState === 'hidden') {
      this.stopTicking();
      return;
    }
    this.startTicking();
    // A hidden tab's timers are throttled, so a transition may have passed unseen.
    const next = this.status()?.nextTransitionAt;
    if (next && Date.parse(next) <= this.serverNow()) {
      this.fetch();
    }
  }
}
