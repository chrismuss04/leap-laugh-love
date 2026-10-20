import { adjustIdea, interpret, payoff, sharesFor, toOrderLine } from './interpret';
import { DataPoint } from './stroke';

/** A line through these prices, one step apart, starting now. */
const line = (...prices: number[]): DataPoint[] => prices.map((price, step) => ({ step, price }));

describe('interpret', () => {
  it('needs a line that goes somewhere', () => {
    expect(interpret([])).toBeNull();
    expect(interpret(line(100))).toBeNull();
    expect(interpret(line(100, 100.2, 99.9, 100.1))?.shape).toBe('flat');
  });

  it('reads a rally as a market buy, profit at the peak, the stop half the profit below', () => {
    const idea = interpret(line(100, 104, 110, 108))!;
    expect(idea).toEqual(expect.objectContaining({
      shape: 'rally', side: 'BUY', entryType: 'MARKET', entry: 100, entryStep: 0, target: 110, targetStep: 2, stop: 95
    }));
  });

  it('reads a dip and a rise as a limit buy at the bottom of the dip', () => {
    const idea = interpret(line(100, 96, 94, 99, 112))!;
    expect(idea).toEqual(expect.objectContaining({
      shape: 'dip-then-rally', entryType: 'LIMIT', entry: 94, entryStep: 2, target: 112, stop: 85
    }));
  });

  it('takes a dip anywhere before the peak as the entry', () => {
    expect(interpret(line(100, 106, 97, 115))).toEqual(expect.objectContaining({ shape: 'dip-then-rally', entry: 97 }));
  });

  it('puts the stop under a fall drawn after the peak', () => {
    const idea = interpret(line(100, 110, 96, 105))!;
    expect(idea).toEqual(expect.objectContaining({ shape: 'rally', entry: 100, target: 110, stop: 95.5 }));
  });

  it('reads a fall as selling now, and a rise then a fall as a limit sell at the peak', () => {
    expect(interpret(line(100, 97, 90), 100, { held: 10 })).toEqual(expect.objectContaining({
      shape: 'fall', side: 'SELL', entryType: 'MARKET', entry: 100, target: 90, stop: null, needsShares: false
    }));
    expect(interpret(line(100, 108, 95), 100, { held: 10 })).toEqual(expect.objectContaining({
      shape: 'rally-then-fall', entryType: 'LIMIT', entry: 108, entryStep: 1
    }));
  });

  it('flags a sell of a stock the account doesn\'t hold', () => {
    expect(interpret(line(100, 90))?.needsShares).toBe(true);
  });

  it('takes the noise band from the options', () => {
    expect(interpret(line(100, 101.5), 100, { band: 0.02 })?.shape).toBe('flat');
    expect(interpret(line(100, 101.5), 100, { band: 0.005 })?.shape).toBe('rally');
  });

  it('keeps dragged levels in order', () => {
    const idea = interpret(line(100, 110))!;
    expect(adjustIdea(idea, { target: 90 }).target).toBe(100.01);
    expect(adjustIdea(idea, { stop: 120 }).stop).toBe(99.99);
    expect(adjustIdea(idea, { stop: 92.456 }).stop).toBe(92.46);
  });

  it('sizes and pays off a buy', () => {
    const idea = interpret(line(100, 110))!;
    expect(sharesFor(idea, 1050)).toBe(10);
    expect(sharesFor(idea, 0)).toBe(0);
    expect(payoff(idea, 10)).toEqual({ reward: 100, risk: 50, ratio: 2 });
  });

  it('caps a sell at the shares held, and counts what it saves', () => {
    const idea = interpret(line(100, 90), 100, { held: 4 })!;
    expect(sharesFor(idea, 1000, 4)).toBe(4);
    expect(payoff(idea, 4)).toEqual({ reward: 40, risk: 0, ratio: null });
  });

  describe('as an order', () => {
    it('places a rally as a market buy with a bracket, which lasts the day', () => {
      expect(toOrderLine('AAPL', interpret(line(100, 110))!, 3, 'GTC')).toEqual({
        symbol: 'AAPL', side: 'BUY', quantity: 3, bracket: { takeProfit: 110, stopLoss: 95 }
      });
    });

    it('keeps a limit buy open as long as the horizon', () => {
      expect(toOrderLine('AAPL', interpret(line(100, 94, 112))!, 2, 'GTC')).toEqual({
        symbol: 'AAPL', side: 'BUY', quantity: 2, orderType: 'LIMIT', limitPrice: 94, timeInForce: 'GTC',
        bracket: { takeProfit: 112, stopLoss: 85 }
      });
    });

    it('places a limit sell at the peak', () => {
      expect(toOrderLine('AAPL', interpret(line(100, 108, 95), 100, { held: 5 })!, 5, 'DAY')).toEqual({
        symbol: 'AAPL', side: 'SELL', quantity: 5, orderType: 'LIMIT', limitPrice: 108
      });
    });

    it('places nothing for a flat line, no shares, or shares not held', () => {
      expect(toOrderLine('AAPL', interpret(line(100, 100.1))!, 3, 'DAY')).toBeNull();
      expect(toOrderLine('AAPL', interpret(line(100, 110))!, 0, 'DAY')).toBeNull();
      expect(toOrderLine('AAPL', interpret(line(100, 90))!, 3, 'DAY')).toBeNull();
    });
  });
});
