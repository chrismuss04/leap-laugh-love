import { convertToParamMap } from '@angular/router';
import {
  EMPTY_AUDIT_FILTER, activeAuditFilters, auditFilterError, auditParams, isOrderId, missingAuditCriteria, normalizeAudit, parseAudit,
  toTradeSearch
} from './audit';

describe('Audit filter', () => {
  const full = {
    from: '2026-10-01T09:00', to: '2026-10-08T17:30', clientId: 'c-1', clientEmail: 'alice@leap.com', accountId: 'a-1',
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
      from: 'yesterday', to: '2026-10-08', side: 'SHORT', status: 'PENDING'
    }))).toEqual(EMPTY_AUDIT_FILTER);
  });

  it('knows every status an order can have, including those of orders that wait for their price', () => {
    for (const status of ['SUBMITTED', 'OPEN', 'ACCEPTED', 'REJECTED', 'FILLED', 'CANCELLED', 'EXPIRED']) {
      expect(parseAudit(convertToParamMap({ status })).status).toBe(status);
    }
  });

  it('trims text, upper-cases symbols, and treats blanks as no filter', () => {
    expect(normalizeAudit({ ...EMPTY_AUDIT_FILTER, clientId: '  c-1 ', clientEmail: ' ', accountId: '   ', symbol: ' msft ' }))
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
    expect(activeAuditFilters(full)).toBe(8);
  });

  it('rejects a reversed period or a client id that is not a full id', () => {
    const base = { ...EMPTY_AUDIT_FILTER, from: '2026-10-08T17:30', to: '2026-10-01T09:00', clientEmail: 'a@b.com' };
    expect(auditFilterError(EMPTY_AUDIT_FILTER)).toContain('time period');
    expect(auditFilterError(base)).toContain('on or before');
    const ok = { ...base, from: '2026-10-01T09:00', to: '2026-10-08T17:30' };
    expect(auditFilterError({ ...ok, clientId: 'c-1' })).toContain('full client id');
    expect(auditFilterError({ ...ok, clientId: '123e4567-e89b-12d3-a456-426614174000' })).toBeNull();
    expect(auditFilterError(ok)).toBeNull();
  });

  it('needs the period and a client, by id or email, before it can be searched', () => {
    const period = { ...EMPTY_AUDIT_FILTER, from: '2026-10-01T09:00', to: '2026-10-08T17:30' };
    expect(missingAuditCriteria(EMPTY_AUDIT_FILTER)).toContain('time period');
    expect(missingAuditCriteria({ ...period, to: null, clientId: 'c-1' })).toContain('time period');
    expect(missingAuditCriteria(period)).toContain('client id or client email');
    expect(missingAuditCriteria({ ...period, clientId: 'c-1' })).toBeNull();
    expect(missingAuditCriteria({ ...period, clientEmail: 'alice@leap.com' })).toBeNull();
  });

  it('recognises order ids, which are UUIDs', () => {
    expect(isOrderId('3f2b8c1e-9a4d-4c2e-8f1a-6b7d5e0c9a21')).toBe(true);
    expect(isOrderId(' 3F2B8C1E-9A4D-4C2E-8F1A-6B7D5E0C9A21 ')).toBe(true);
    expect(isOrderId('3f2b8c1e')).toBe(false);
    expect(isOrderId('')).toBe(false);
    expect(isOrderId(null)).toBe(false);
  });

  it('searches trades over the period, with to running to the end of its minute', () => {
    const filter = {
      ...EMPTY_AUDIT_FILTER, from: '2026-10-01T09:00', to: '2026-10-08T17:30', clientEmail: 'alice@leap.com', side: 'BUY' as const
    };
    expect(toTradeSearch(filter)).toEqual(expect.objectContaining({
      from: new Date('2026-10-01T09:00').toISOString(),
      to: new Date('2026-10-08T17:31').toISOString(),
      clientEmail: 'alice@leap.com',
      clientId: undefined,
      side: 'BUY'
    }));
  });

  it('searches an account by id when it is a UUID, otherwise by account number', () => {
    const filter = { ...EMPTY_AUDIT_FILTER, from: '2026-10-01T09:00', to: '2026-10-08T17:30', clientId: 'c-1' };
    const id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
    expect(toTradeSearch({ ...filter, accountId: id })).toEqual(expect.objectContaining({ accountId: id, accountNumber: undefined }));
    expect(toTradeSearch({ ...filter, accountId: 'ACC-1001' }))
      .toEqual(expect.objectContaining({ accountId: undefined, accountNumber: 'ACC-1001' }));
  });
});
