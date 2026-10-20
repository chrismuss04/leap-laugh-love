import { NgZone } from '@angular/core';
import { TestBed } from '@angular/core/testing';

/**
 * TestBed.tick(), run inside the app's NgZone. Use it in place of TestBed.tick() in specs that
 * keep zone-based change detection.
 *
 * Called from the test's own zone, a tick runs component effects inside NgZone. Leaving NgZone after
 * one can find it stable, so the zone scheduler starts a second tick inside the first and Angular
 * throws NG0101 ("ApplicationRef.tick is called recursively"). That only happens when no microtask
 * is pending at that moment, so it shows on a loaded CI agent and not on a fast machine. Inside
 * NgZone, the tick finishes before the zone can settle.
 */
export function tickInZone(): void {
  TestBed.inject(NgZone).run(() => TestBed.tick());
}
