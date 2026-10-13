import { bracketProblem, riskReward, riskRewardLabel } from './bracket';

describe('riskReward', () => {
  it('works out risk, reward and the ratio', () => {
    const value = riskReward(100, 125, 88, 25)!;
    expect(value.risk).toBe(300);
    expect(value.reward).toBe(625);
    expect(riskRewardLabel(riskReward(100, 130, 88, 10)!)).toBe('Risk $120.00 · Reward $300.00 · 1 : 2.5');
  });

  it('has nothing to say for exits on the wrong side of the entry', () => {
    expect(riskReward(100, 95, 90, 1)).toBeNull();
    expect(riskReward(100, 120, 105, 1)).toBeNull();
    expect(riskReward(100, 120, 90, 0)).toBeNull();
  });
});

describe('bracketProblem', () => {
  it('asks for both prices, then checks them against the entry', () => {
    expect(bracketProblem(100, null, 90)).toBe('Enter a take-profit price');
    expect(bracketProblem(100, 110, null)).toBe('Enter a stop-loss price');
    expect(bracketProblem(100, 100, 90)).toBe('The take-profit must be above the price you buy at');
    expect(bracketProblem(100, 110, 100)).toBe('The stop-loss must be below the price you buy at');
    expect(bracketProblem(100, 110, 90)).toBeNull();
  });
});
