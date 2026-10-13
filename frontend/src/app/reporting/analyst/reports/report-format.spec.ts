import { barWidth, change, compactMoney, count, monthLabel, notableTrend, percent, trend } from './report-format';

// Analyst reports: the formatting every 12-month report shares, including a missing figure.
describe('Analyst report formatting', () => {
  it('formats months in UTC, so the first of a month never slips into the one before', () => {
    expect(monthLabel('2026-01')).toBe('Jan 2026');
    expect(monthLabel('2025-12')).toBe('Dec 2025');
  });

  it('shows a dash for a missing figure', () => {
    expect(compactMoney(null)).toBe('—');
    expect(compactMoney(undefined)).toBe('—');
    expect(count(null)).toBe('—');
    expect(percent(null)).toBe('—');
    expect(change(undefined)).toBe('—');
  });

  it('formats money compactly, counts in full and percentages to one place', () => {
    expect(compactMoney(47_400_000)).toBe('$47.4M');
    expect(compactMoney(0)).toBe('$0');
    expect(count(12345)).toBe('12,345');
    expect(percent(45)).toBe('45.0%');
  });

  it('always signs a change, so its direction never relies on colour alone', () => {
    expect(change(36.9)).toBe('+36.9%');
    expect(change(-0.8)).toBe('−0.8%');
    expect(change(0)).toBe('0.0%');
  });

  it('colours any change by direction, and a notable one only from 10% either way', () => {
    expect(trend(5)).toBe('gain');
    expect(trend(-5)).toBe('loss');
    expect(trend(0)).toBe('flat');
    expect(trend(null)).toBe('flat');

    expect(notableTrend(10)).toBe('gain');
    expect(notableTrend(-10)).toBe('loss');
    expect(notableTrend(9.9)).toBe('flat');
    expect(notableTrend(null)).toBe('flat');
  });

  it('scales a bar to its maximum, clamped to 0-100, and draws nothing with no maximum', () => {
    expect(barWidth(50, 200)).toBe(25);
    expect(barWidth(300, 200)).toBe(100);
    expect(barWidth(-5, 200)).toBe(0);
    expect(barWidth(null, 200)).toBe(0);
    expect(barWidth(50, 0)).toBe(0);
  });
});
