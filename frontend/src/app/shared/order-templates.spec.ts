import { OrderTemplate } from '../services/order-templates';
import { TicketSetup, applyTemplate, bracketPrices, suggestedTemplateName, templateFromTicket } from './order-templates';

const ticket: TicketSetup = {
  side: 'BUY', orderType: 'MARKET', timeInForce: 'DAY', sidePrice: 100, limitPrice: null, stopPrice: null,
  trailUnit: 'PERCENT', trailValue: null, entryPrice: 100, takeProfit: null, stopLoss: null
};

describe('order templates', () => {
  it('saves a bracket buy as percents from its entry price', () => {
    const template = templateFromTicket('  Swing  ', { ...ticket, takeProfit: 120, stopLoss: 92 })!;
    expect(template).toEqual(expect.objectContaining({
      name: 'Swing', side: 'BUY', orderType: 'MARKET', timeInForce: 'DAY',
      takeProfitPercent: 20, stopLossPercent: 8, limitOffsetPercent: null, stopOffsetPercent: null, trailPercent: null
    }));
  });

  it('saves limit and stop prices as signed offsets from the market, and keeps the time in force', () => {
    const limit = templateFromTicket('l', { ...ticket, orderType: 'LIMIT', limitPrice: 99, timeInForce: 'GTC' })!;
    expect(limit.limitOffsetPercent).toBe(-1);
    expect(limit.timeInForce).toBe('GTC');
    const stopLimit = templateFromTicket('s', { ...ticket, side: 'SELL', orderType: 'STOP_LIMIT', stopPrice: 95, limitPrice: 94.5 })!;
    expect(stopLimit.stopOffsetPercent).toBe(-5);
    expect(stopLimit.limitOffsetPercent).toBe(-5.5);
    expect(stopLimit.takeProfitPercent).toBeNull();
  });

  it('saves a trailing stop in dollars as a percent of the price', () => {
    expect(templateFromTicket('t', { ...ticket, side: 'SELL', orderType: 'TRAILING_STOP', trailUnit: 'AMOUNT', trailValue: 2.5 })!.trailPercent).toBe(2.5);
    expect(templateFromTicket('t', { ...ticket, orderType: 'TRAILING_STOP', trailUnit: 'PERCENT', trailValue: 3 })!.trailPercent).toBe(3);
  });

  it('cannot be saved while a price the type needs is missing', () => {
    expect(templateFromTicket('x', { ...ticket, orderType: 'LIMIT' })).toBeNull();
    expect(templateFromTicket('x', { ...ticket, orderType: 'STOP' })).toBeNull();
    expect(templateFromTicket('x', { ...ticket, orderType: 'TRAILING_STOP' })).toBeNull();
    expect(templateFromTicket('x', { ...ticket, sidePrice: 0 })).toBeNull();
  });

  const saved: OrderTemplate = {
    templateId: 't1', name: 'n', side: 'BUY', orderType: 'LIMIT', timeInForce: 'GTC', limitOffsetPercent: -1,
    stopOffsetPercent: null, trailPercent: null, takeProfitPercent: 20, stopLossPercent: 8, createdAt: ''
  };

  it('applies as prices against whatever the market is today', () => {
    expect(applyTemplate(saved, 100).limitPrice).toBe(99);
    expect(applyTemplate(saved, 250).limitPrice).toBe(247.5);
    expect(applyTemplate({ ...saved, stopOffsetPercent: -8, limitOffsetPercent: null }, 150).stopPrice).toBe(138);
    expect(bracketPrices(99, 20, 8)).toEqual({ takeProfit: 118.8, stopLoss: 91.08 });
  });

  it('names a template from its setup', () => {
    expect(suggestedTemplateName({ ...saved, orderType: 'MARKET', limitOffsetPercent: null, timeInForce: 'DAY' }))
      .toBe('Buy, stop-loss −8%, take-profit +20%');
    expect(suggestedTemplateName({
      ...saved, side: 'SELL', orderType: 'STOP', limitOffsetPercent: null, stopOffsetPercent: -8,
      takeProfitPercent: null, stopLossPercent: null
    })).toBe('Sell, stop −8%, until cancelled');
    expect(suggestedTemplateName({ ...saved, name: '' }).length).toBeLessThanOrEqual(40);
  });
});
