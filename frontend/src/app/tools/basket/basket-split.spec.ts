import { dividerStep, dragDivider, evenSplit, moveDivider, parseCents, rescale, setAmount } from './basket-split';

describe('basket split', () => {
  const sum = (amounts: number[]) => amounts.reduce((a, b) => a + b, 0);

  it('splits evenly, the odd cents going to the first stocks', () => {
    expect(evenSplit(100000, 3)).toEqual([33334, 33333, 33333]);
    expect(evenSplit(0, 2)).toEqual([0, 0]);
  });

  it('steps dividers by 1% of the total, and at least a cent', () => {
    expect(dividerStep(250000)).toBe(2500);
    expect(dividerStep(40)).toBe(1);
  });

  it('keeps each stock\'s share when the total changes', () => {
    expect(rescale([50000, 25000, 25000], 200000)).toEqual([100000, 50000, 50000]);
    expect(sum(rescale([33334, 33333, 33333], 99999))).toBe(99999);
  });

  it('starts an empty split evenly', () => {
    expect(rescale([0, 0], 1001)).toEqual([501, 500]);
  });

  it('moves money between the two stocks beside a divider', () => {
    expect(moveDivider([50000, 30000, 20000], 1, 5000)).toEqual([50000, 35000, 15000]);
    expect(moveDivider([50000, 30000, 20000], 0, -10000)).toEqual([40000, 40000, 20000]);
  });

  it('stops a divider when a stock reaches $0', () => {
    expect(moveDivider([50000, 30000, 20000], 1, 50000)).toEqual([50000, 50000, 0]);
  });

  it('drags a divider to a point on the bar, snapped to the step', () => {
    // A $1,000 basket: the first divider dragged to $612.34 snaps to $610.
    expect(dragDivider([50000, 50000], 0, 61234, 1000)).toEqual([61000, 39000]);
  });

  it('keeps the total when one amount is typed, spreading the difference in proportion', () => {
    expect(setAmount([40000, 40000, 20000], 0, 70000)).toEqual([70000, 20000, 10000]);
    expect(setAmount([40000, 40000, 20000], 2, 999999)).toEqual([0, 0, 100000]);
  });

  it('reads typed dollars as cents', () => {
    expect(parseCents('2,500')).toBe(250000);
    expect(parseCents('$1234.5')).toBe(123450);
    expect(parseCents('0.07')).toBe(7);
    expect(parseCents('12.345')).toBeNull();
    expect(parseCents('abc')).toBeNull();
    expect(parseCents('')).toBeNull();
  });
});
