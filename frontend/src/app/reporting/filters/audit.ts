import { ParamMap, Params } from '@angular/router';

/**
 * Staff Dashboards: what trading operations narrows the order audit to. Every field is optional
 * and kept in the URL under its own name, so an audit view can be bookmarked or shared.
 */
export const ORDER_STATUSES = ['SUBMITTED', 'ACCEPTED', 'REJECTED', 'FILLED'] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];

export const ORDER_SIDES = ['BUY', 'SELL'] as const;
export type OrderSide = (typeof ORDER_SIDES)[number];

export interface AuditFilter {
  /** Submitted at or after, as a datetime-local value (yyyy-mm-ddThh:mm). */
  from: string | null;
  /** Submitted at or before, as a datetime-local value (yyyy-mm-ddThh:mm). */
  to: string | null;
  clientId: string | null;
  accountId: string | null;
  symbol: string | null;
  side: OrderSide | null;
  status: OrderStatus | null;
}

export const EMPTY_AUDIT_FILTER: AuditFilter = {
  from: null, to: null, clientId: null, accountId: null, symbol: null, side: null, status: null
};

const DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Reads the filter from the URL, dropping any value that couldn't have come from the filter bar. */
export function parseAudit(params: ParamMap): AuditFilter {
  return normalizeAudit({
    from: params.get('from'),
    to: params.get('to'),
    clientId: params.get('clientId'),
    accountId: params.get('accountId'),
    symbol: params.get('symbol'),
    side: params.get('side') as OrderSide | null,
    status: params.get('status') as OrderStatus | null
  });
}

/** Trims and upper-cases what needs it, and turns blank or unrecognised values into null. */
export function normalizeAudit(filter: AuditFilter): AuditFilter {
  const text = (value: string | null) => value?.trim() || null;
  return {
    from: filter.from && DATE_TIME.test(filter.from) ? filter.from : null,
    to: filter.to && DATE_TIME.test(filter.to) ? filter.to : null,
    clientId: text(filter.clientId),
    accountId: text(filter.accountId),
    symbol: text(filter.symbol)?.toUpperCase() ?? null,
    side: ORDER_SIDES.includes(filter.side!) ? filter.side : null,
    status: ORDER_STATUSES.includes(filter.status!) ? filter.status : null
  };
}

/** The query params for a filter; null clears a param, so an empty filter leaves a clean URL. */
export function auditParams(filter: AuditFilter): Params {
  return { ...filter };
}

/** How many fields narrow the audit, for the filter bar's summary. */
export function activeAuditFilters(filter: AuditFilter): number {
  return Object.values(filter).filter(value => value !== null).length;
}

/** Whether the text is an order id. Orders are keyed by UUID (trading.orders.order_id). */
export function isOrderId(value: string | null | undefined): boolean {
  return !!value && UUID.test(value.trim());
}
