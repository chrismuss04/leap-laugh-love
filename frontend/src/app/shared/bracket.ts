/** Take-profit and stop-loss presets, as percent from the entry price. */
export const TAKE_PROFIT_PERCENTS = [5, 10, 20];
export const STOP_LOSS_PERCENTS = [3, 5, 10];

export interface RiskReward {
  /** What the stop-loss would lose, in dollars. */
  risk: number;
  /** What the take-profit would earn, in dollars. */
  reward: number;
  /** Reward per dollar risked, e.g. 2.5 for 1 : 2.5. */
  ratio: number;
}

/** The dollars at risk and at stake for a bracket on a buy of `quantity` shares at `entry`. */
export function riskReward(entry: number, takeProfit: number, stopLoss: number, quantity: number): RiskReward | null {
  if (!(entry > 0) || !(quantity > 0) || !(takeProfit > entry) || !(stopLoss > 0) || !(stopLoss < entry)) {
    return null;
  }
  const risk = (entry - stopLoss) * quantity;
  const reward = (takeProfit - entry) * quantity;
  return { risk, reward, ratio: reward / risk };
}

/** "Risk $120.00 · Reward $300.00 · 1 : 2.5" */
export function riskRewardLabel(value: RiskReward): string {
  const money = (amount: number) => '$' + amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return `Risk ${money(value.risk)} · Reward ${money(value.reward)} · 1 : ${+value.ratio.toFixed(1)}`;
}

/** Why a bracket's prices don't fit the entry, or null if they do. */
export function bracketProblem(entry: number | null, takeProfit: number | null, stopLoss: number | null): string | null {
  if (takeProfit == null || !(takeProfit > 0)) {
    return 'Enter a take-profit price';
  }
  if (stopLoss == null || !(stopLoss > 0)) {
    return 'Enter a stop-loss price';
  }
  if (entry != null && takeProfit <= entry) {
    return 'The take-profit must be above the price you buy at';
  }
  if (entry != null && stopLoss >= entry) {
    return 'The stop-loss must be below the price you buy at';
  }
  return null;
}
