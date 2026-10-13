import { OrderSide } from '../services/order';

/** Past this share of the account in one stock, the ticket warns about concentration. */
export const CONCENTRATION_WARNING = 0.35;

export interface ImpactInput {
  side: OrderSide;
  quantity: number;
  /** Per-share price the order would execute at: the live price, or the limit or stop price. */
  price: number;
  /** The stock's live price, for valuing the position afterwards. */
  marketPrice: number;
  sharesOwned: number;
  averageCost: number | null;
  /** Buying power now: cash less what open orders hold. */
  buyingPower: number;
  /** The account's total value (cash plus shares at live prices), or null if unknown. */
  totalValue: number | null;
  /** A bracket's stop-loss on a buy, for the worst case. */
  stopLossPrice?: number | null;
}

export interface OrderImpact {
  sharesAfter: number;
  positionValueAfter: number;
  /** Fraction (0-1) of the account's value in this stock afterwards, or null if the value is unknown. */
  portfolioShare: number | null;
  cashAfter: number;
  /** A buy's new average cost per share; null for a sell that empties the position. */
  averageCostAfter: number | null;
  /** A sell's realized gain or loss against average cost; null for a buy or unknown cost. */
  realized: number | null;
  /** A bracket buy's loss if its stop-loss is hit, as a positive number. */
  worstCaseLoss: number | null;
  /** True when the position is large enough to warrant a warning. */
  concentrated: boolean;
}

/** What placing the order would do to the position and the account, before it is submitted. */
export function orderImpact(input: ImpactInput): OrderImpact {
  const { side, quantity, price, marketPrice, sharesOwned, averageCost, buyingPower, totalValue } = input;
  const buy = side === 'BUY';
  const sharesAfter = buy ? sharesOwned + quantity : Math.max(0, sharesOwned - quantity);
  const positionValueAfter = sharesAfter * marketPrice;
  const portfolioShare = totalValue != null && totalValue > 0 ? positionValueAfter / totalValue : null;
  const notional = quantity * price;

  let averageCostAfter: number | null;
  if (buy) {
    averageCostAfter = ((averageCost ?? price) * sharesOwned + notional) / sharesAfter;
  } else {
    averageCostAfter = sharesAfter > 0 ? averageCost : null;
  }

  const stop = input.stopLossPrice;
  const stopBelowPrice = stop != null && stop > 0 && stop < price;
  return {
    sharesAfter,
    positionValueAfter,
    portfolioShare,
    cashAfter: buy ? buyingPower - notional : buyingPower + notional,
    averageCostAfter,
    realized: !buy && averageCost != null ? quantity * (price - averageCost) : null,
    worstCaseLoss: buy && stopBelowPrice ? quantity * (price - stop) : null,
    concentrated: buy && portfolioShare != null && portfolioShare >= CONCENTRATION_WARNING
  };
}

/** "AAPL would be 38% of your portfolio". */
export function concentrationWarning(symbol: string, share: number): string {
  return `${symbol} would be ${Math.round(share * 100)}% of your portfolio.`;
}
