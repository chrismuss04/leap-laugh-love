import {
  AllocationHolding, CASH_COLOR, SLICE_PALETTE, buildAllocation, donutLabels, formatShare, sliceArcs, spreadLabels
} from './allocation';

describe('Rebalance allocation', () => {
  const holding = (symbol: string, quantity: number, price: number | null): AllocationHolding =>
    ({ symbol, name: `${symbol} Inc`, quantity, price });

  describe('slice sizing', () => {
    it('sizes each holding by quantity × live price, with cash as its own slice', () => {
      const { total, slices } = buildAllocation([holding('AAPL', 10, 150), holding('MSFT', 2, 400)], 700);
      expect(total).toBe(3000);
      expect(slices.map(s => [s.key, s.value, s.share])).toEqual([
        ['AAPL', 1500, 0.5], ['MSFT', 800, 800 / 3000], ['cash', 700, 700 / 3000]
      ]);
      expect(slices.reduce((sum, s) => sum + s.share, 0)).toBeCloseTo(1, 10);
    });

    it('orders holdings largest first and keeps cash last', () => {
      const { slices } = buildAllocation([holding('A', 1, 10), holding('B', 1, 90), holding('C', 1, 50)], 1000);
      expect(slices.map(s => s.key)).toEqual(['B', 'C', 'A', 'cash']);
    });

    it('colours cash grey and never gives a holding that grey', () => {
      const many = Array.from({ length: 12 }, (_, i) => holding(`S${i}`, 1, 100 - i));
      const { slices } = buildAllocation(many, 50);
      expect(slices.at(-1)).toMatchObject({ kind: 'cash', label: 'Cash', color: CASH_COLOR, shares: null });
      expect(slices.filter(s => s.kind === 'holding').every(s => s.color !== CASH_COLOR)).toBe(true);
      expect(SLICE_PALETTE).not.toContain(CASH_COLOR);
    });

    it('leaves out holdings without a price yet, and says which', () => {
      const result = buildAllocation([holding('AAPL', 10, 150), holding('NEW', 5, null)], 500);
      expect(result.slices.map(s => s.key)).toEqual(['AAPL', 'cash']);
      expect(result.unpriced).toEqual(['NEW']);
      expect(result.total).toBe(2000);
    });

    it('shows only the cash slice for an account with no holdings', () => {
      const { slices, total } = buildAllocation([], 2500);
      expect(total).toBe(2500);
      expect(slices).toEqual([expect.objectContaining({ key: 'cash', share: 1, value: 2500 })]);
    });

    it('still draws a cash ring for an account with nothing in it', () => {
      const { slices, total } = buildAllocation([], 0);
      expect(total).toBe(0);
      expect(slices).toEqual([expect.objectContaining({ key: 'cash', share: 1, value: 0 })]);
    });

    it('resizes slices as live prices move', () => {
      const before = buildAllocation([holding('AAPL', 10, 100), holding('MSFT', 10, 100)], 0);
      const after = buildAllocation([holding('AAPL', 10, 300), holding('MSFT', 10, 100)], 0);
      expect(before.slices.map(s => s.share)).toEqual([0.5, 0.5]);
      expect(after.slices.map(s => [s.key, s.share])).toEqual([['AAPL', 0.75], ['MSFT', 0.25]]);
      expect(after.total).toBe(4000);
    });
  });

  describe("'Other' grouping", () => {
    // 97% AAPL, then three small holdings at 1% each.
    const small = () => buildAllocation(
      [holding('AAPL', 97, 100), holding('X', 1, 100), holding('Y', 1, 100), holding('Z', 1, 100)], 0);

    it('marks slices under 3% as grouped, but keeps each as its own slice', () => {
      const { slices } = small();
      expect(slices.map(s => [s.key, s.grouped])).toEqual([['AAPL', false], ['X', true], ['Y', true], ['Z', true]]);
    });

    it('gives the grouped slices one shared Other label with their combined share', () => {
      const { slices } = small();
      const labels = donutLabels(slices, sliceArcs(slices));
      expect(labels.map(l => [l.text, l.keys])).toEqual([['AAPL', ['AAPL']], ['Other', ['X', 'Y', 'Z']]]);
      expect(labels[1].share).toBeCloseTo(0.03, 10);
    });

    it('centres the Other label on the grouped arc', () => {
      const { slices } = small();
      const arcs = sliceArcs(slices);
      const other = donutLabels(slices, arcs)[1];
      expect(other.angle).toBeCloseTo((arcs[1].start + arcs[3].end) / 2, 10);
    });

    it('labels a slice of exactly 3% on its own', () => {
      const { slices } = buildAllocation([holding('BIG', 97, 100), holding('EDGE', 3, 100)], 0);
      expect(slices.find(s => s.key === 'EDGE')!.grouped).toBe(false);
    });

    it('never groups cash, however small', () => {
      const { slices } = buildAllocation([holding('AAPL', 99, 100)], 100);
      const labels = donutLabels(slices, sliceArcs(slices));
      expect(slices.at(-1)!.grouped).toBe(false);
      expect(labels.map(l => l.text)).toEqual(['AAPL', 'Cash']);
    });

    it('regroups as prices move a holding across the threshold', () => {
      const at = (price: number) => buildAllocation([holding('AAPL', 10, 100), holding('TINY', 1, price)], 0)
        .slices.find(s => s.key === 'TINY')!.grouped;
      expect(at(20)).toBe(true);   // 20 / 1020 ≈ 2%
      expect(at(40)).toBe(false);  // 40 / 1040 ≈ 3.8%
    });
  });

  describe('arcs and labels', () => {
    it('lays arcs end to end clockwise from 12 o\'clock around the full circle', () => {
      const arcs = sliceArcs([{ key: 'a', share: 0.25 }, { key: 'b', share: 0.75 }]);
      expect(arcs[0]).toEqual({ key: 'a', start: 0, end: Math.PI / 2 });
      expect(arcs[1].start).toBe(Math.PI / 2);
      expect(arcs[1].end).toBeCloseTo(Math.PI * 2, 10);
    });

    it('formats shares as whole percents, with a decimal under 10%', () => {
      expect(formatShare(0.6)).toBe('60%');
      expect(formatShare(0.042)).toBe('4.2%');
      expect(formatShare(0.0004)).toBe('<0.1%');
      expect(formatShare(0)).toBe('0%');
    });

    it('spreads crowded labels apart and keeps them inside the chart', () => {
      expect(spreadLabels([50, 52, 54], 20, 0, 200)).toEqual([50, 70, 90]);
      expect(spreadLabels([195, 196], 20, 0, 200)).toEqual([180, 200]);
      // Order in, order out, even when the input isn't sorted.
      expect(spreadLabels([100, 10], 20, 0, 200)).toEqual([100, 10]);
    });
  });

  describe('contrast (WCAG AA)', () => {
    const luminance = (hex: string) => {
      const [r, g, b] = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255)
        .map(v => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    const contrast = (a: string, b: string) => {
      const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
      return (hi + 0.05) / (lo + 0.05);
    };
    // --surface and --background from styles.css.
    const surfaces = ['#121216', '#0d0d0f'];

    it('keeps every slice colour, cash included, at 3:1 or more against the page', () => {
      for (const color of [...SLICE_PALETTE, CASH_COLOR]) {
        for (const surface of surfaces) {
          expect(contrast(color, surface), `${color} on ${surface}`).toBeGreaterThanOrEqual(3);
        }
      }
    });
  });
});
