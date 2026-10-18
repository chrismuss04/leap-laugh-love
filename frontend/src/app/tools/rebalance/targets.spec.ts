import {
  FINE_STEP, STEP, WHOLE, apportion, boundaryPosition, dragBoundary, equalWeight, formatTarget, moveBoundary,
  parseTarget, setTarget, targetsFromShares
} from './targets';

describe('Rebalance targets', () => {
  const sum = (targets: number[]) => targets.reduce((a, b) => a + b, 0);
  const none = (n: number) => new Array<boolean>(n).fill(false);

  describe('sum-to-100 invariant', () => {
    it('apportions shares into tenths that sum to exactly 100%', () => {
      expect(targetsFromShares([1 / 3, 1 / 3, 1 / 3])).toEqual([334, 333, 333]);
      expect(targetsFromShares([0.29, 0.71])).toEqual([290, 710]);
      expect(sum(targetsFromShares([0.1234, 0.5678, 0.3088]))).toBe(WHOLE);
    });

    it('splits evenly when every weight is zero', () => {
      expect(apportion([0, 0, 0], 10)).toEqual([4, 3, 3]);
    });

    it('keeps the sum at 100% through any sequence of drags, nudges, typing and shortcuts', () => {
      // A deterministic pseudo-random walk over every operation.
      let seed = 7;
      const rand = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
      let targets = targetsFromShares([0.4, 0.25, 0.2, 0.1, 0.05]);
      const locked = [false, false, true, false, false];
      for (let step = 0; step < 500; step++) {
        const boundary = Math.floor(rand() * 4);
        switch (step % 4) {
          case 0: targets = dragBoundary(targets, locked, boundary, rand() * WHOLE, rand() < 0.5 ? STEP : FINE_STEP); break;
          case 1: targets = moveBoundary(targets, locked, boundary, Math.round((rand() - 0.5) * 200)); break;
          case 2: targets = setTarget(targets, locked, Math.floor(rand() * 5), rand() * WHOLE); break;
          default: targets = equalWeight(targets, locked, [true, true, true, true, false]);
        }
        expect(sum(targets)).toBe(WHOLE);
        expect(targets.every(t => t >= 0 && Number.isInteger(t))).toBe(true);
      }
    });
  });

  describe('dragging a boundary', () => {
    const start = [500, 300, 200];

    it('grows the slice before the boundary and shrinks the one after', () => {
      expect(dragBoundary(start, none(3), 0, 600, STEP)).toEqual([600, 200, 200]);
      expect(dragBoundary(start, none(3), 1, 700, STEP)).toEqual([500, 200, 300]);
    });

    it('snaps to whole percentages', () => {
      expect(dragBoundary(start, none(3), 0, 563, STEP)).toEqual([560, 240, 200]);
      expect(dragBoundary(start, none(3), 0, 567, STEP)).toEqual([570, 230, 200]);
    });

    it('moves in 0.1% steps with the fine step (Shift)', () => {
      expect(dragBoundary(start, none(3), 0, 563.4, FINE_STEP)).toEqual([563, 237, 200]);
    });

    it('snaps the dragged slice even when the ones before it are fractional', () => {
      // The first boundary sits at 33.3%; dragging the second to ~70% makes the middle slice 37%.
      expect(dragBoundary([333, 333, 334], none(3), 1, 702, STEP)).toEqual([333, 370, 297]);
    });

    it('drags a slice down to 0% and no further', () => {
      expect(dragBoundary(start, none(3), 0, 950, STEP)).toEqual([800, 0, 200]);
      expect(dragBoundary(start, none(3), 0, -40, STEP)).toEqual([0, 800, 200]);
    });

    it('drags a 0% slice back from either side', () => {
      const sold = [800, 0, 200];
      expect(dragBoundary(sold, none(3), 1, 900, STEP)).toEqual([800, 100, 100]);
      expect(dragBoundary(sold, none(3), 0, 700, STEP)).toEqual([700, 100, 200]);
    });

    it('treats cash like any other slice', () => {
      // Holdings then cash: dragging the last boundary keeps more cash uninvested.
      expect(dragBoundary([700, 300], none(2), 0, 600, STEP)).toEqual([600, 400]);
    });

    it('reports where a boundary sits', () => {
      expect(boundaryPosition(start, 0)).toBe(500);
      expect(boundaryPosition(start, 1)).toBe(800);
    });
  });

  describe('locking', () => {
    it('keeps a locked slice at its target, carrying it along as the boundary moves', () => {
      // B is locked: the boundary between A and B trades A against C, and B rides along.
      const next = dragBoundary([400, 300, 300], [false, true, false], 0, 500, STEP);
      expect(next).toEqual([500, 300, 200]);
    });

    it('carries locked slices on the growing side too', () => {
      // B is locked: dragging the B|C boundary to 75% grows A, and B keeps its 30%.
      const next = dragBoundary([400, 300, 300], [false, true, false], 1, 750, STEP);
      expect(next).toEqual([450, 300, 250]);
      expect(boundaryPosition(next, 1)).toBe(750);
    });

    it("doesn't move a boundary with no unlocked slice on one side", () => {
      const targets = [400, 300, 300];
      expect(moveBoundary(targets, [false, true, true], 1, 50)).toBe(targets);
      expect(dragBoundary(targets, [true, false, true], 0, 700, STEP)).toBe(targets);
    });

    it('spreads a typed target over the unlocked slices only', () => {
      expect(setTarget([400, 300, 300], [false, true, false], 0, 600)).toEqual([600, 300, 100]);
    });

    it('caps a typed target at what the locked slices leave', () => {
      expect(setTarget([400, 300, 300], [false, true, false], 0, 900)).toEqual([700, 300, 0]);
    });

    it("won't type into a locked slice, or the last unlocked one", () => {
      const targets = [400, 300, 300];
      expect(setTarget(targets, [true, false, false], 0, 100)).toBe(targets);
      expect(setTarget(targets, [false, true, true], 0, 100)).toBe(targets);
    });
  });

  describe('typing a target', () => {
    it('spreads the difference in proportion to the other targets', () => {
      expect(setTarget([500, 300, 200], none(3), 0, 250)).toEqual([250, 450, 300]);
    });

    it('can set a slice to 0%', () => {
      expect(setTarget([500, 300, 200], none(3), 1, 0)).toEqual([714, 0, 286]);
    });

    it('reads typed percentages as tenths', () => {
      expect(parseTarget('25')).toBe(250);
      expect(parseTarget(' 33.33% ')).toBe(333);
      expect(parseTarget('.5')).toBe(5);
      expect(parseTarget('abc')).toBeNull();
      expect(parseTarget('-5')).toBeNull();
      expect(parseTarget('')).toBeNull();
    });

    it('formats targets with a tenth only when they have one', () => {
      expect(formatTarget(250)).toBe('25%');
      expect(formatTarget(333)).toBe('33.3%');
      expect(formatTarget(0)).toBe('0%');
    });
  });

  describe('equal weight', () => {
    const holdings = [true, true, true, false];

    it('gives every holding the same target and leaves cash alone', () => {
      expect(equalWeight([500, 200, 200, 100], none(4), holdings)).toEqual([300, 300, 300, 100]);
    });

    it('splits the remainder of uneven shares to the first holdings', () => {
      expect(equalWeight([500, 300, 200, 0], none(4), holdings)).toEqual([334, 333, 333, 0]);
    });

    it('works around locked holdings', () => {
      expect(equalWeight([500, 200, 200, 100], [true, false, false, false], holdings)).toEqual([500, 200, 200, 100]);
      expect(equalWeight([400, 100, 300, 200], [true, false, false, false], holdings)).toEqual([400, 200, 200, 200]);
    });
  });
});
