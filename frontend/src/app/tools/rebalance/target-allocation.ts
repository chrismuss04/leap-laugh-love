import { Injectable, computed, signal } from '@angular/core';
import { Allocation, CASH_COLOR, DonutSlice, SLICE_PALETTE } from './allocation';
import {
  WHOLE, boundarySides, dragBoundary, equalWeight, formatTarget, moveBoundary, setTarget, targetsFromShares
} from './targets';

/**
 * Rebalance Donut: the client's target allocation for one account, in tenths of a percent, in a
 * fixed ring order. Until the client changes a target, targets follow the current allocation as
 * prices tick; once they do, targets hold still. Provided by the Rebalance tool, so the donut and
 * the targets table edit one set of targets. Every change made by keyboard, typing, a finished
 * drag or a shortcut is put into `announcement` for a polite live region.
 */
@Injectable()
export class TargetAllocation {
  /** Slice keys in ring order, fixed until the set of slices changes, so boundaries don't jump as prices move. */
  readonly keys = signal<string[]>([]);
  /** Each slice's target in tenths of a percent, in ring order; always sums to 1000. */
  readonly targets = signal<number[]>([]);
  readonly locked = signal<ReadonlySet<string>>(new Set());
  /** Whether the client has changed a target since the last reset. */
  readonly edited = signal(false);
  readonly announcement = signal('');

  readonly lockedFlags = computed(() => {
    const locked = this.locked();
    return this.keys().map(key => locked.has(key));
  });

  private readonly labels = signal<Record<string, string>>({});
  private readonly kinds = signal<Record<string, DonutSlice['kind']>>({});
  private readonly shares = signal<Record<string, number>>({});

  private scope: string | null = null;

  /**
   * Takes in the live allocation: starts afresh for another account or when its slices change,
   * else follows it until edited.
   */
  sync(allocation: Allocation | null, scope: string | null = null): void {
    const slices = allocation?.slices ?? [];
    this.labels.set(Object.fromEntries(slices.map(s => [s.key, s.label])));
    this.kinds.set(Object.fromEntries(slices.map(s => [s.key, s.kind])));
    this.shares.set(Object.fromEntries(slices.map(s => [s.key, s.share])));
    const keys = slices.map(s => s.key);
    const current = this.keys();
    const sameSet = scope === this.scope && keys.length === current.length && keys.every(key => current.includes(key));
    this.scope = scope;
    if (!sameSet) {
      this.keys.set(keys);
      this.locked.set(new Set());
      this.edited.set(false);
    }
    if (!sameSet || !this.edited()) {
      this.targets.set(this.currentTargets());
    }
  }

  /** The allocation's slices in ring order, coloured by their place in it so colours don't swap as values cross. */
  order(allocation: Allocation | null): Allocation | null {
    if (!allocation) {
      return null;
    }
    const keys = this.keys();
    const rank = (slice: DonutSlice) => {
      const i = keys.indexOf(slice.key);
      return i < 0 ? keys.length : i;
    };
    const holdings = allocation.slices.filter(s => s.kind === 'holding').sort((a, b) => rank(a) - rank(b));
    const slices = [
      ...holdings.map((s, i) => ({ ...s, color: SLICE_PALETTE[i % SLICE_PALETTE.length] })),
      ...allocation.slices.filter(s => s.kind === 'cash').map(s => ({ ...s, color: CASH_COLOR }))
    ];
    return { ...allocation, slices };
  }

  targetOf(key: string): number | null {
    const i = this.keys().indexOf(key);
    return i < 0 ? null : this.targets()[i] ?? null;
  }

  isLocked(key: string): boolean {
    return this.locked().has(key);
  }

  /** A pointer drag's step: moves boundary `boundary` towards `position` tenths. Announced when the drag ends. */
  dragTo(boundary: number, position: number, step: number): void {
    this.apply(dragBoundary(this.targets(), this.lockedFlags(), boundary, position, step));
  }

  /** Says where a dragged boundary ended up. */
  finishDrag(boundary: number): void {
    this.announceBoundary(boundary);
  }

  /** Arrow keys: moves a boundary by `delta` tenths. */
  nudge(boundary: number, delta: number): void {
    if (this.apply(moveBoundary(this.targets(), this.lockedFlags(), boundary, delta))) {
      this.announceBoundary(boundary);
    } else {
      this.announce(this.boundaryText(boundary) + '. Can’t move further.');
    }
  }

  /** A target typed into the table, in tenths. */
  type(key: string, tenths: number): void {
    const i = this.keys().indexOf(key);
    if (i < 0) {
      return;
    }
    this.apply(setTarget(this.targets(), this.lockedFlags(), i, tenths));
    this.announce(`${this.label(key)} target ${formatTarget(this.targets()[i])}`);
  }

  toggleLock(key: string): void {
    const locked = new Set(this.locked());
    const nowLocked = !locked.delete(key);
    if (nowLocked) {
      locked.add(key);
    }
    this.locked.set(locked);
    const target = formatTarget(this.targetOf(key) ?? 0);
    this.announce(nowLocked ? `${this.label(key)} locked at ${target}` : `${this.label(key)} unlocked`);
  }

  /** Back to the current allocation. Locks stay, so a client can reset around them. */
  reset(): void {
    this.targets.set(this.currentTargets());
    this.edited.set(false);
    this.announce('Targets reset to your current allocation');
  }

  /** Every unlocked holding to the same target; cash and locked holdings keep theirs. */
  equalWeight(): void {
    const kinds = this.kinds();
    const keys = this.keys();
    const next = equalWeight(this.targets(), this.lockedFlags(), keys.map(key => kinds[key] === 'holding'));
    if (this.apply(next)) {
      const open = keys.filter((key, i) => kinds[key] === 'holding' && !this.lockedFlags()[i]);
      const each = [...new Set(open.map(key => formatTarget(this.targetOf(key)!)))];
      this.announce(`Each holding set to ${each.join(' or ')}`);
    }
  }

  /** Whether the slices on either side of a boundary (or the slices it carries) can trade at all. */
  canMove(boundary: number): boolean {
    return boundarySides(this.lockedFlags(), boundary) !== null;
  }

  /** "AAPL target 41%, MSFT target 19%": the two slices a boundary trades between. */
  boundaryText(boundary: number): string {
    const sides = boundarySides(this.lockedFlags(), boundary);
    const keys = this.keys();
    const targets = this.targets();
    const pair = sides ? [sides.before, sides.after] : [boundary, boundary + 1];
    return pair.map(i => `${this.label(keys[i])} target ${formatTarget(targets[i])}`).join(', ');
  }

  label(key: string): string {
    return this.labels()[key] ?? key;
  }

  private announceBoundary(boundary: number): void {
    this.announce(this.boundaryText(boundary));
  }

  /** Re-setting the same text wouldn't be announced again, so it's cleared first. */
  private announce(text: string): void {
    this.announcement.set('');
    queueMicrotask(() => this.announcement.set(text));
  }

  private apply(next: number[]): boolean {
    if (next === this.targets()) {
      return false;
    }
    this.targets.set(next);
    this.edited.set(true);
    return true;
  }

  private currentTargets(): number[] {
    const shares = this.shares();
    const targets = targetsFromShares(this.keys().map(key => shares[key] ?? 0));
    // An account worth nothing still has a ring: all of it in its one slice.
    return targets.reduce((a, b) => a + b, 0) === WHOLE ? targets : this.keys().map((_, i) => (i === 0 ? WHOLE : 0));
  }
}
