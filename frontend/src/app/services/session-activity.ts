// Session Timeout & Revocation: only browser interaction reports activity, never API traffic.
import { DOCUMENT } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Injectable, NgZone, OnDestroy, inject } from '@angular/core';
import { Subject, Subscription, fromEvent, merge } from 'rxjs';
import { throttleTime } from 'rxjs/operators';
import { AuthService } from './auth.service';

@Injectable({ providedIn: 'root' })
export class SessionActivityService implements OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly http = inject(HttpClient);
  private readonly zone = inject(NgZone);
  private readonly document = inject(DOCUMENT);
  private readonly idleMs = 10 * 60 * 1000;
  private readonly storageKey = 'session_activity';
  private subscriptions = new Subscription();
  private timer: ReturnType<typeof setTimeout> | undefined;
  private token: string | null = null;
  private sessionId: string | null = null;
  private expiresAt = 0;
  private lastActivity = 0;
  private reporting = false;
  private readonly expiredSubject = new Subject<void>();

  // Step 6 will use this notification to clear login state and display the expiry message.
  readonly expired$ = this.expiredSubject.asObservable();

  start(): void {
    this.stop();
    const token = this.auth.getToken();
    if (!token) return;
    try {
      // Decoding is only for local timing; the backend verifies the signature and session state.
      const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      const claims = JSON.parse(atob(payload.padEnd(Math.ceil(payload.length / 4) * 4, '=')));
      if (typeof claims.sid !== 'string' || !Number.isFinite(claims.iat) || !Number.isFinite(claims.exp)) {
        throw new Error('Missing session claims');
      }
      this.token = token;
      this.sessionId = claims.sid;
      this.expiresAt = claims.exp * 1000;
      this.lastActivity = claims.iat * 1000;
      this.readSharedActivity();
    } catch {
      this.expire();
      return;
    }
    if (this.isExpired()) {
      this.expire();
      return;
    }

    this.zone.runOutsideAngular(() => {
      // Limit noisy pointer/scroll events to one report per second, with no automatic heartbeat.
      const events = ['pointermove', 'pointerdown', 'keydown', 'wheel', 'touchstart'];
      this.subscriptions.add(merge(...events.map(name =>
        fromEvent<Event>(this.document, name, { passive: true })
      )).pipe(throttleTime(1000)).subscribe(event => {
        if (event.isTrusted && this.document.visibilityState === 'visible') this.interact();
      }));
      // Returning to a tab checks elapsed time; it doesn't count as activity or revive a session.
      this.subscriptions.add(fromEvent(this.document, 'visibilitychange').subscribe(() => this.checkTime()));
      const view = this.document.defaultView;
      if (view) {
        this.subscriptions.add(fromEvent(view, 'focus').subscribe(() => this.checkTime()));
        this.subscriptions.add(fromEvent<StorageEvent>(view, 'storage').subscribe(event => {
          if (event.key === 'auth_token' && this.auth.getToken() !== this.token) {
            this.stop();
          } else if (event.key === this.storageKey) {
            this.checkTime();
          }
        }));
      }
      this.schedule();
    });
    this.subscriptions.add(this.auth.isAuthenticated$.subscribe(authenticated => {
      if (!authenticated) this.stop();
    }));
  }

  private interact(): void {
    if (!this.token || this.auth.getToken() !== this.token) {
      this.stop();
      return;
    }
    this.readSharedActivity();
    if (this.isExpired()) {
      this.expire();
      return;
    }
    if (this.reporting) return;
    const activityTime = Date.now();
    this.reporting = true;
    this.subscriptions.add(this.http.post<void>('/api/iam/session/activity', {}).subscribe({
      next: () => {
        this.reporting = false;
        // Use the interaction time, not response time, so slow responses don't extend inactivity.
        this.lastActivity = Math.max(this.lastActivity, activityTime);
        try {
          localStorage.setItem(this.storageKey, JSON.stringify({
            sessionId: this.sessionId, lastActivity: this.lastActivity
          }));
        } catch { /* Server enforcement still applies when browser storage is unavailable. */ }
        this.schedule();
      },
      error: error => {
        this.reporting = false;
        if (error.status === 401) this.expire();
        // Network/503 failures do not count as accepted activity; the next interaction can retry.
      }
    }));
  }

  private readSharedActivity(): void {
    try {
      const saved = JSON.parse(localStorage.getItem(this.storageKey) ?? 'null');
      if (saved?.sessionId === this.sessionId && Number.isFinite(saved.lastActivity)
          && saved.lastActivity <= Date.now()) {
        this.lastActivity = Math.max(this.lastActivity, saved.lastActivity);
      }
    } catch { /* Ignore missing or malformed local timing hints. */ }
  }

  private isExpired(): boolean {
    return Date.now() >= Math.min(this.lastActivity + this.idleMs, this.expiresAt);
  }

  private checkTime(): void {
    if (!this.token) return;
    this.readSharedActivity();
    if (this.isExpired()) this.expire();
    else this.schedule();
  }

  private schedule(): void {
    if (this.timer !== undefined) clearTimeout(this.timer);
    const remaining = Math.min(this.lastActivity + this.idleMs, this.expiresAt) - Date.now();
    this.timer = setTimeout(() => this.checkTime(), Math.max(0, remaining));
  }

  private expire(): void {
    this.stop();
    this.zone.run(() => this.expiredSubject.next());
  }

  stop(): void {
    this.subscriptions.unsubscribe();
    this.subscriptions = new Subscription();
    if (this.timer !== undefined) clearTimeout(this.timer);
    this.timer = undefined;
    this.token = null;
    this.sessionId = null;
    this.reporting = false;
  }

  ngOnDestroy(): void {
    this.stop();
  }
}
