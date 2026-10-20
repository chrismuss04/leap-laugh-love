import { formatMoney, formatSignedMoney } from '../../shared/format';
import { DrawShape, Payoff, TradeIdea } from './interpret';

/**
 * Draw Your Trade: the trade a line makes, said in words.
 *
 * Plain TypeScript with no Angular dependencies. A sentence comes back in parts so the workspace
 * can set the figures in bold and colour the gain and loss.
 */

/** A short name for each shape, for the badge over the trade. */
export const SHAPE_NAMES: Record<DrawShape, string> = {
  'rally': 'Ride the rally',
  'dip-then-rally': 'Buy the dip',
  'rally-then-fall': 'Sell the peak',
  'fall': 'Get out early',
  'flat': 'Flat line'
};

export interface SentencePart {
  text: string;
  /** Set for the figures, which are bold; 'gain' and 'loss' are coloured too. */
  tone?: 'figure' | 'gain' | 'loss';
}

const figure = (text: string): SentencePart => ({ text, tone: 'figure' });
const plain = (text: string): SentencePart => ({ text });

const shares = (count: number, symbol: string) => figure(`${count} ${symbol}`);

/**
 * The trade in a sentence: what to do, how many shares, and what it makes or risks. A sell the
 * account can't make, or a flat line, says what to draw instead.
 */
export function tradeSentence(symbol: string, idea: TradeIdea, count: number, payoff: Payoff): SentencePart[] {
  const money = formatMoney;
  if (idea.shape === 'flat') {
    return [plain('That line stays flat. Draw a move up or down to make a trade.')];
  }
  if (idea.needsShares) {
    return [plain(`You'd need to own ${symbol} to sell it. Draw a line that ends higher to buy instead.`)];
  }
  if (idea.side === 'BUY') {
    const exit = [
      plain('. Sell at '), figure(money(idea.target)), plain(' for '), { text: formatSignedMoney(payoff.reward), tone: 'gain' },
      plain(', or bail at '), figure(money(idea.stop)), plain(' for '), { text: formatSignedMoney(-payoff.risk), tone: 'loss' },
      plain('.')
    ] as SentencePart[];
    return idea.entryType === 'LIMIT'
      ? [plain('Buy '), shares(count, symbol), plain(' if it dips to '), figure(money(idea.entry)), ...exit]
      : [plain('Buy '), shares(count, symbol), plain(' now at about '), figure(money(idea.entry)), ...exit];
  }
  const dodge = [plain(', dodging a '), { text: money(payoff.reward), tone: 'loss' }, plain(' drop to '),
    figure(money(idea.target)), plain('.')] as SentencePart[];
  return idea.entryType === 'LIMIT'
    ? [plain('Sell '), shares(count, symbol), plain(' if it rises to '), figure(money(idea.entry)), ...dodge]
    : [plain('Sell '), shares(count, symbol), plain(' now at about '), figure(money(idea.entry)), ...dodge];
}

/** The sentence as plain text, for screen readers and tests. */
export function sentenceText(parts: readonly SentencePart[]): string {
  return parts.map(p => p.text).join('');
}

/** The most shares the trade can be: what the cash buys at the entry, or what's held for a sell. */
export function maxShares(idea: TradeIdea, cash: number, held: number): number {
  if (idea.side === 'SELL') {
    return Math.max(Math.floor(held), 0);
  }
  return idea.entry > 0 && cash > 0 ? Math.floor(cash / idea.entry + 1e-9) : 0;
}

/** Shares to start a new trade at: a quarter of what the cash buys, or every share held for a sell. */
export function defaultShares(idea: TradeIdea, cash: number, held: number): number {
  const max = maxShares(idea, cash, held);
  return idea.side === 'SELL' ? max : Math.min(max, Math.max(1, Math.round(max / 4)));
}

/** The risk and reward as shares (0-1) of a bar, risk first; even when there's neither. */
export function riskRewardSplit(payoff: Payoff): { risk: number; reward: number } {
  const total = payoff.risk + payoff.reward;
  return total > 0 ? { risk: payoff.risk / total, reward: payoff.reward / total } : { risk: 0.5, reward: 0.5 };
}
