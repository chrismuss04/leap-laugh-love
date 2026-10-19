import { TestBed } from '@angular/core/testing';
import { Allocation, DonutSlice, buildAllocation } from './allocation';
import { TargetAllocation } from './target-allocation';

describe('TargetAllocation', () => {
  let targets: TargetAllocation;
  const holding = (symbol: string, quantity: number) => ({ symbol, name: symbol, quantity, price: 100 });
  // AAPL 50%, MSFT 30%, cash 20%.
  const allocation = () => buildAllocation([holding('AAPL', 5), holding('MSFT', 3)], 200, { alwaysCash: true });
  const values = () => targets.keys().map((key, i) => [key, targets.targets()[i]]);
  const slice = (key: string, share: number, extra: Partial<DonutSlice> = {}): DonutSlice => ({
    key, kind: key === 'cash' ? 'cash' : 'holding', label: key, name: key, shares: 0, value: 0, share, color: '', grouped: false,
    ...extra
  });

  async function announced(): Promise<string> {
    await Promise.resolve();
    return targets.announcement();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [TargetAllocation] });
    targets = TestBed.inject(TargetAllocation);
    targets.sync(allocation(), 'a1');
  });

  describe('adding a stock', () => {
    it('refuses a stock already in the donut, and says so', async () => {
      expect(targets.add('aapl', 'Apple')).toBe(false);
      expect(await announced()).toBe('AAPL is already in your donut');
      expect(targets.keys()).toEqual(['AAPL', 'MSFT', 'cash']);
    });

    it('goes at the end when there is no cash slice', () => {
      targets.sync({ total: 800, unpriced: [], slices: [slice('AAPL', 0.6), slice('MSFT', 0.4)] }, 'a2');
      targets.add('NVDA', 'NVIDIA');
      expect(targets.keys()).toEqual(['AAPL', 'MSFT', 'NVDA']);
      expect(targets.targetOf('NVDA')).toBe(50);
    });

    it('comes in at 0% when every other slice is locked, and says why', async () => {
      targets.toggleLock('AAPL');
      targets.toggleLock('MSFT');
      targets.toggleLock('cash');
      targets.add('NVDA', 'NVIDIA');
      expect(targets.targetOf('NVDA')).toBe(0);
      expect(await announced()).toBe('NVDA added at 0%: every other slice is locked');
    });

    it('falls back to the symbol when there is no name', () => {
      targets.add('NVDA', '');
      expect(targets.added()).toEqual([{ symbol: 'NVDA', name: 'NVDA' }]);
      expect(targets.label('NVDA')).toBe('NVDA');
    });
  });

  describe('removing a stock', () => {
    it('only removes stocks that were added', () => {
      const before = values();
      targets.remove('AAPL');
      targets.remove('NOPE');
      expect(values()).toEqual(before);
    });

    it('drops the lock of a locked added stock', async () => {
      targets.add('NVDA', 'NVIDIA');
      targets.toggleLock('NVDA');
      targets.remove('NVDA');
      expect(targets.isLocked('NVDA')).toBe(false);
      expect(targets.keys()).toEqual(['AAPL', 'MSFT', 'cash']);
      expect(await announced()).toBe('NVDA removed from your donut');
    });
  });

  describe('syncing with the live allocation', () => {
    it('forgets added stocks on another account', () => {
      targets.add('NVDA', 'NVIDIA');
      targets.sync(allocation(), 'a2');
      expect(targets.added()).toEqual([]);
    });

    it('treats an added stock that has been bought as an ordinary holding', () => {
      targets.add('NVDA', 'NVIDIA');
      const bought: Allocation = {
        total: 1000, unpriced: [],
        slices: [slice('AAPL', 0.5), slice('MSFT', 0.3), slice('NVDA', 0.05), slice('cash', 0.15)]
      };
      targets.sync(bought, 'a1');
      expect(targets.added()).toEqual([]);
      expect(targets.isAdded('NVDA')).toBe(false);
    });

    it('keeps an added stock while its slice is still marked new', () => {
      targets.add('NVDA', 'NVIDIA');
      const withNew: Allocation = {
        ...allocation(), slices: [...allocation().slices.slice(0, 2), slice('NVDA', 0, { added: true }), allocation().slices[2]]
      };
      targets.sync(withNew, 'a1');
      expect(targets.isAdded('NVDA')).toBe(true);
      expect(targets.targetOf('NVDA')).toBe(50);
    });

    it('splits the ring evenly when nothing has a value, so it still adds up to 100%', () => {
      targets.sync({ total: 0, unpriced: [], slices: [slice('AAPL', 0), slice('cash', 0)] }, 'a3');
      expect(values()).toEqual([['AAPL', 500], ['cash', 500]]);
    });

    it('orders slices it has no place for after the rest', () => {
      const extra: Allocation = { ...allocation(), slices: [slice('ZZZ', 0), ...allocation().slices] };
      expect(targets.order(extra)!.slices.map(s => s.key)).toEqual(['AAPL', 'MSFT', 'ZZZ', 'cash']);
      expect(targets.order(null)).toBeNull();
    });
  });

  describe('edge cases', () => {
    it('ignores a typed target for a slice it does not have', () => {
      const before = values();
      targets.type('NOPE', 500);
      expect(values()).toEqual(before);
      expect(targets.targetOf('NOPE')).toBeNull();
    });

    it('does nothing for Equal weight when every holding is locked', async () => {
      targets.toggleLock('AAPL');
      targets.toggleLock('MSFT');
      await announced();
      targets.equalWeight();
      expect(await announced()).toBe('MSFT locked at 30%');
      expect(targets.edited()).toBe(false);
    });

    it('knows which boundaries can move', () => {
      expect(targets.canMove(0)).toBe(true);
      targets.toggleLock('MSFT');
      targets.toggleLock('cash');
      expect(targets.canMove(1)).toBe(false);
    });

    it('labels a key it has no slice for with the key itself', () => {
      expect(targets.label('ZZZ')).toBe('ZZZ');
    });

    it('announces locking a key it has no target for at 0%', async () => {
      targets.toggleLock('ZZZ');
      expect(await announced()).toBe('ZZZ locked at 0%');
    });
  });
});
