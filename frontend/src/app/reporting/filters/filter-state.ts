import { Injectable, Signal, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap, Params, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuditFilter, EMPTY_AUDIT_FILTER, auditParams, parseAudit } from './audit';
import { DateRange, PeriodFilter, parsePeriod, periodParams, resolvePeriod } from './period';

/**
 * Staff Dashboards: a dashboard's filter, held in the URL's query params. Each dashboard provides
 * its own, so any report rendered inside it can inject the filter and read `value()` - the
 * dashboard owns the filter bar, and reports never need to agree on filters among themselves.
 */
@Injectable()
export abstract class QueryFilterState<T> {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /** The current filter, updated whenever the URL changes (including back and forward). */
  readonly value: Signal<T> = toSignal(this.route.queryParamMap.pipe(map(params => this.parse(params))),
    { requireSync: true });

  /** Applies a filter by updating the URL, which updates `value`. */
  set(filter: T): void {
    this.router.navigate([], { relativeTo: this.route, queryParams: this.toParams(filter), queryParamsHandling: 'merge' });
  }

  protected abstract parse(params: ParamMap): T;
  protected abstract toParams(filter: T): Params;
}

/** The commercial analyst's reporting period. */
@Injectable()
export class PeriodFilterState extends QueryFilterState<PeriodFilter> {
  /** The days the period covers, for reports to query by. */
  readonly range: Signal<DateRange> = computed(() => resolvePeriod(this.value()));

  protected parse(params: ParamMap): PeriodFilter {
    return parsePeriod(params);
  }

  protected toParams(filter: PeriodFilter): Params {
    return periodParams(filter);
  }
}

/** Trading operations' order audit filter. */
@Injectable()
export class AuditFilterState extends QueryFilterState<AuditFilter> {
  reset(): void {
    this.set(EMPTY_AUDIT_FILTER);
  }

  protected parse(params: ParamMap): AuditFilter {
    return parseAudit(params);
  }

  protected toParams(filter: AuditFilter): Params {
    return auditParams(filter);
  }
}
