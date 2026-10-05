import { direction, formatMoney, formatNumber, formatPhone, formatSignedMoney, formatSignedPercent, percentChange } from './format';
import { countryName } from './countries';
import { isCashAmount } from '../accounts/cash';

describe('format helpers', () => {
  it('formats money, with a dash for missing values', () => {
    expect(formatMoney(1234.5)).toBe('$1,234.50');
    expect(formatMoney(null)).toBe('—');
    expect(formatMoney(NaN)).toBe('—');
  });

  it('signs money so direction never relies on colour', () => {
    expect(formatSignedMoney(10)).toBe('+$10.00');
    expect(formatSignedMoney(-10)).toBe('−$10.00');
    expect(formatSignedMoney(0)).toBe('$0.00');
    expect(formatSignedMoney(undefined)).toBe('—');
  });

  it('signs percentages', () => {
    expect(formatSignedPercent(1.234)).toBe('+1.23%');
    expect(formatSignedPercent(-1.234)).toBe('−1.23%');
    expect(formatSignedPercent(0)).toBe('0.00%');
    expect(formatSignedPercent(Infinity)).toBe('—');
    expect(formatSignedPercent(null)).toBe('—');
  });

  it('formats plain numbers', () => {
    expect(formatNumber(4812.1)).toBe('4,812.10');
    expect(formatNumber(null)).toBe('—');
  });

  it('classifies direction', () => {
    expect(direction(1)).toBe('gain');
    expect(direction(-1)).toBe('loss');
    expect(direction(0)).toBe('flat');
    expect(direction(null)).toBe('flat');
    expect(direction(NaN)).toBe('flat');
  });

  it('computes percent change only against a usable baseline', () => {
    expect(percentChange(110, 100)).toBeCloseTo(10);
    expect(percentChange(90, -100)).toBeCloseTo(190);
    expect(percentChange(null, 100)).toBeNull();
    expect(percentChange(100, null)).toBeNull();
    expect(percentChange(100, 0)).toBeNull();
  });

  it('formats a phone number as it is typed', () => {
    expect(formatPhone('')).toBe('');
    expect(formatPhone('55')).toBe('(55');
    expect(formatPhone('5551')).toBe('(555) 1');
    expect(formatPhone('5551234567')).toBe('(555) 123-4567');
    expect(formatPhone('+1 555 123 4567')).toBe('(555) 123-4567');
    expect(formatPhone('55512345678999')).toBe('(555) 123-4567');
  });

  it('looks up country names, falling back to the code', () => {
    expect(countryName('US')).toBe('United States');
    expect(countryName('ZZ')).toBe('ZZ');
  });

  it('accepts only whole-cent cash amounts of at least one cent', () => {
    expect(isCashAmount(0.29)).toBeTrue();
    expect(isCashAmount(100)).toBeTrue();
    expect(isCashAmount(0.001)).toBeFalse();
    expect(isCashAmount(1.234)).toBeFalse();
    expect(isCashAmount(0)).toBeFalse();
    expect(isCashAmount(null)).toBeFalse();
  });
});
