import { ParamMap, Params } from '@angular/router';
import { TradeSearch } from '../../services/trade-ops';

/**
 * Staff Dashboards: what trading operations narrows the order audit to, kept in the URL under each
 * field's own name so an audit view can be bookmarked or shared. A search needs the period and the
 * client (by id or email); every other field is optional.
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
  clientEmail: string | null;
  /** An account id or account number. */
  accountId: string | null;
  symbol: string | null;
  side: OrderSide | null;
  status: OrderStatus | null;
}

export const EMPTY_AUDIT_FILTER: AuditFilter = {
  from: null, to: null, clientId: null, clientEmail: null, accountId: null, symbol: null, side: null, status: null
};

const DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Reads the filter from the URL, dropping any value that couldn't have come from the filter bar. */
export function parseAudit(params: ParamMap): AuditFilter {
  return normalizeAudit({
    from: params.get('from'),
    to: params.get('to'),
    clientId: params.get('clientId'),
    clientEmail: params.get('clientEmail'),
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
    clientEmail: text(filter.clientEmail),
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

/** Whether the text is a UUID, as orders, clients and accounts are keyed by. */
export function isUuid(value: string | null | undefined): boolean {
  return !!value && UUID.test(value.trim());
}

/** Whether the text is an order id. Orders are keyed by UUID (trading.orders.order_id). */
export function isOrderId(value: string | null | undefined): boolean {
  return isUuid(value);
}

/** Why the filter can't be searched yet, or null when it can: it needs a period and a client. */
export function missingAuditCriteria(filter: AuditFilter): string | null {
  if (!filter.from || !filter.to) {
    return 'Enter a time period: both "Submitted from" and "Submitted to".';
  }
  if (!filter.clientId && !filter.clientEmail) {
    return 'Enter a client id or client email.';
  }
  return null;
}

/**
 * The trade search for a filter that can be searched. Submitted from and to are local
 * datetime-local minutes and both inclusive, so the search runs from the start of the first to the
 * end of the last; an account that isn't a UUID is an account number.
 */
export function toTradeSearch(filter: AuditFilter): TradeSearch {
  const account = filter.accountId ?? undefined;
  return {
    from: new Date(filter.from!).toISOString(),
    to: new Date(new Date(filter.to!).getTime() + 60_000).toISOString(),
    clientId: filter.clientId ?? undefined,
    clientEmail: filter.clientEmail ?? undefined,
    accountId: isUuid(account) ? account : undefined,
    accountNumber: account && !isUuid(account) ? account : undefined,
    symbol: filter.symbol ?? undefined,
    side: filter.side ?? undefined,
    status: filter.status ?? undefined
  };
}
