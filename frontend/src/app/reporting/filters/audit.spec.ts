import { convertToParamMap } from '@angular/router';
import { EMPTY_AUDIT_FILTER, activeAuditFilters, auditParams, isOrderId, normalizeAudit, parseAudit } from './audit';

describe('Audit filter', () => {
  const full = {
    from: '2026-10-01T09:00', to: '2026-10-08T17:30', clientId: 'c-1', accountId: 'a-1',
    symbol: 'AAPL', side: 'BUY' as const, status: 'REJECTED' as const
  };

  it('reads every field from the URL', () => {
    expect(parseAudit(convertToParamMap(full))).toEqual(full);
  });

  it('is empty when the URL has no filters', () => {
    expect(parseAudit(convertToParamMap({}))).toEqual(EMPTY_AUDIT_FILTER);
  });

  it('drops values the filter bar could not have produced', () => {
    expect(parseAudit(convertToParamMap({
      from: 'yesterday', to: '2026-10-08', side: 'SHORT', status: 'CANCELLED'
    }))).toEqual(EMPTY_AUDIT_FILTER);
  });

  it('trims text, upper-cases symbols, and treats blanks as no filter', () => {
    expect(normalizeAudit({ ...EMPTY_AUDIT_FILTER, clientId: '  c-1 ', accountId: '   ', symbol: ' msft ' }))
      .toEqual({ ...EMPTY_AUDIT_FILTER, clientId: 'c-1', symbol: 'MSFT' });
  });

  it('round-trips through the URL, clearing unset fields', () => {
    const filter = { ...EMPTY_AUDIT_FILTER, symbol: 'AAPL', status: 'FILLED' as const };
    const params = auditParams(filter);
    expect(params['clientId']).toBeNull();
    expect(parseAudit(convertToParamMap(Object.fromEntries(
      Object.entries(params).filter(([, value]) => value !== null)
    )))).toEqual(filter);
  });

  it('counts the fields in use', () => {
    expect(activeAuditFilters(EMPTY_AUDIT_FILTER)).toBe(0);
    expect(activeAuditFilters(full)).toBe(7);
  });

  it('recognises order ids, which are UUIDs', () => {
    expect(isOrderId('3f2b8c1e-9a4d-4c2e-8f1a-6b7d5e0c9a21')).toBeTrue();
    expect(isOrderId(' 3F2B8C1E-9A4D-4C2E-8F1A-6B7D5E0C9A21 ')).toBeTrue();
    expect(isOrderId('3f2b8c1e')).toBeFalse();
    expect(isOrderId('')).toBeFalse();
    expect(isOrderId(null)).toBeFalse();
  });
});
