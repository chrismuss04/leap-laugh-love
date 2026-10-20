import { interpret, payoff } from './interpret';
import { DataPoint } from './stroke';
import { SHAPE_NAMES, defaultShares, maxShares, riskRewardSplit, sentenceText, tradeSentence } from './trade-summary';

const line = (...prices: number[]): DataPoint[] => prices.map((price, step) => ({ step, price }));
const idea = (prices: number[], held = 0) => interpret(line(...prices), prices[0], { held })!;
const say = (prices: number[], count: number, held = 0) => {
  const i = idea(prices, held);
  return sentenceText(tradeSentence('AAPL', i, count, payoff(i, count)));
};

describe('trade summary', () => {
  it('names every shape', () => {
    expect(SHAPE_NAMES['dip-then-rally']).toBe('Buy the dip');
    expect(Object.keys(SHAPE_NAMES).length).toBe(5);
  });

  it('says a buy with what it makes and risks', () => {
    expect(say([100, 110], 10)).toBe('Buy 10 AAPL now at about $100.00. Sell at $110.00 for +$100.00, or bail at $95.00 for −$50.00.');
    expect(say([100, 94, 112], 2)).toBe('Buy 2 AAPL if it dips to $94.00. Sell at $112.00 for +$36.00, or bail at $85.00 for −$18.00.');
  });

  it('says a sell with the drop it dodges', () => {
    expect(say([100, 90], 3, 3)).toBe('Sell 3 AAPL now at about $100.00, dodging a $30.00 drop to $90.00.');
    expect(say([100, 108, 95], 1, 5)).toBe('Sell 1 AAPL if it rises to $108.00, dodging a $13.00 drop to $95.00.');
  });

  it('says what to draw instead of a trade it can\'t make', () => {
    expect(say([100, 90], 0)).toContain("You'd need to own AAPL");
    expect(say([100, 100.1], 0)).toContain('stays flat');
  });

  it('marks the figures and colours the gain and loss', () => {
    const i = idea([100, 110]);
    const parts = tradeSentence('AAPL', i, 1, payoff(i, 1));
    expect(parts.filter(p => p.tone === 'gain').map(p => p.text)).toEqual(['+$10.00']);
    expect(parts.filter(p => p.tone === 'loss').map(p => p.text)).toEqual(['−$5.00']);
    expect(parts.find(p => p.text === '1 AAPL')?.tone).toBe('figure');
  });

  it('sizes a buy by cash and a sell by shares held', () => {
    expect(maxShares(idea([100, 110]), 1050, 0)).toBe(10);
    expect(defaultShares(idea([100, 110]), 1050, 0)).toBe(3);
    expect(defaultShares(idea([100, 110]), 150, 0)).toBe(1);
    expect(defaultShares(idea([100, 110]), 50, 0)).toBe(0);
    expect(maxShares(idea([100, 90], 7), 1e6, 7)).toBe(7);
    expect(defaultShares(idea([100, 90], 7), 1e6, 7)).toBe(7);
  });

  it('splits a bar between risk and reward', () => {
    expect(riskRewardSplit({ reward: 30, risk: 10, ratio: 3 })).toEqual({ risk: 0.25, reward: 0.75 });
    expect(riskRewardSplit({ reward: 0, risk: 0, ratio: null })).toEqual({ risk: 0.5, reward: 0.5 });
  });
});
