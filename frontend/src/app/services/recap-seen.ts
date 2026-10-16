import { Injectable, computed, signal } from '@angular/core';

const STORAGE_KEY = 'recap_seen_month';

/** Last month in UTC as yyyy-MM: the month whose recap is the newest. */
export function lastRecapMonth(now: Date = new Date()): string {
  const year = now.getUTCMonth() === 0 ? now.getUTCFullYear() - 1 : now.getUTCFullYear();
  const month = now.getUTCMonth() === 0 ? 12 : now.getUTCMonth();
  return `${year}-${String(month).padStart(2, '0')}`;
}

/**
 * Whether the client has opened the newest monthly recap, for the "new" marks on the Recap tab and
 * menu item. Remembered in this browser only: it is a hint, so another device or a private window
 * just shows the mark once more. Storage can be unavailable or cleared, so every access is guarded.
 */
@Injectable({ providedIn: 'root' })
export class RecapSeenService {
  private readonly seenMonth = signal(read());

  /** True until the client opens last month's recap. */
  readonly unseen = computed(() => this.seenMonth() !== lastRecapMonth());

  /** Records that the client has seen a month's recap. */
  markSeen(month: string): void {
    this.seenMonth.set(month);
    try {
      localStorage.setItem(STORAGE_KEY, month);
    } catch {
      // Not remembered past this page load; the mark comes back next visit.
    }
  }
}

function read(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}
