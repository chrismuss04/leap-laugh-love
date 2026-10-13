import { Type } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  ActivationCohort, ActivationReport, ClientActivityMonth, ClientActivityReport, ConcentrationMonth,
  ConcentrationReport, OrderSizeBand, OrderSizeMonth, OrderSizeReport, RetentionCohort, RetentionReport,
  ScorecardMetric, ScorecardMonth, ScorecardReport
} from '../../../services/reports';
import { ActivationReportComponent } from './activation-report';
import { ClientActivityReportComponent } from './client-activity-report';
import { ConcentrationReportComponent } from './concentration-report';
import { OrderSizeReportComponent } from './order-size-report';
import { RetentionReportComponent } from './retention-report';
import { ScorecardReportComponent } from './scorecard-report';
import { ReportWindowState } from './report-window-state';

// Analyst reports: each 12-month report component against canned reporting-app responses.

const WINDOW = { firstMonth: '2025-10', lastMonth: '2026-09' };
const MONTHS = Array.from({ length: 12 }, (_, i) => {
  const month = new Date(Date.UTC(2025, 9 + i, 1));
  return `${month.getUTCFullYear()}-${String(month.getUTCMonth() + 1).padStart(2, '0')}`;
});
const SEP = '2026-09';

const metric = (value: number | null, mom: number | null = null, yoy: number | null = null): ScorecardMetric =>
  ({ value, monthOverMonthPct: mom, yearOverYearPct: yoy });

function scorecard(overrides: Partial<Record<string, ScorecardMonth>> = {}): ScorecardReport {
  return {
    window: WINDOW,
    months: MONTHS.map(month => overrides[month] ?? {
      month, volume: metric(0), filledOrders: metric(0), averageTradeValue: metric(null),
      activeClients: metric(0), registrations: metric(0)
    })
  };
}

function clientActivity(overrides: Partial<Record<string, ClientActivityMonth>> = {}): ClientActivityReport {
  return {
    window: WINDOW,
    months: MONTHS.map(month => overrides[month] ?? {
      month, activeClients: 0, newClients: 0, returningClients: 0, reactivatedClients: 0, lapsedClients: 0
    })
  };
}

function retention(sizes: Partial<Record<string, number>> = {}, pct: (offset: number) => number = () => 50): RetentionReport {
  return {
    window: WINDOW,
    cohorts: MONTHS.map((cohort, start): RetentionCohort => {
      const clients = sizes[cohort] ?? 0;
      return {
        cohort, clients,
        months: MONTHS.slice(start).map((month, offset) => ({
          monthsSinceSignUp: offset, month,
          activeClients: clients === 0 ? 0 : Math.round(clients * pct(offset) / 100),
          activePct: clients === 0 ? null : pct(offset)
        }))
      };
    })
  };
}

function activation(overrides: Partial<Record<string, Partial<ActivationCohort>>> = {}): ActivationReport {
  return {
    window: WINDOW,
    cohorts: MONTHS.map(cohort => ({
      cohort, registered: 0, orderedWithin30Days: 0, orderedWithin30DaysPct: null, orderedEver: 0,
      orderedEverPct: null, medianDaysToFirstOrder: null, complete: cohort !== SEP, ...overrides[cohort]
    }))
  };
}

function concentration(overrides: Partial<Record<string, ConcentrationMonth>> = {}): ConcentrationReport {
  return {
    window: WINDOW,
    months: MONTHS.map(month => overrides[month] ?? { month, tradingClients: 0, volume: 0, tiers: [] })
  };
}

function bands(orders: number[] = [0, 0, 0, 0, 0]): OrderSizeBand[] {
  const bounds: [OrderSizeBand['band'], number | null, number | null][] = [
    ['UNDER_1K', null, 1000], ['FROM_1K', 1000, 5000], ['FROM_5K', 5000, 10000], ['FROM_10K', 10000, 25000], ['FROM_25K', 25000, null]
  ];
  return bounds.map(([band, from, to], i) => ({ band, from, to, orders: orders[i], value: orders[i] * 100 }));
}

function orderSizes(overrides: Partial<Record<string, OrderSizeMonth>> = {}): OrderSizeReport {
  return {
    window: WINDOW,
    months: MONTHS.map(month => overrides[month] ?? { month, filledOrders: 0, meanValue: null, medianValue: null, bands: bands() })
  };
}

interface Case {
  name: string;
  component: Type<unknown>;
  url: string;
  empty: object;
  withData: object;
}

const CASES: Case[] = [
  {
    name: 'scorecard', component: ScorecardReportComponent, url: '/api/reporting/analyst/scorecard', empty: scorecard(),
    withData: scorecard({ [SEP]: { month: SEP, volume: metric(1200), filledOrders: metric(8), averageTradeValue: metric(150),
      activeClients: metric(30), registrations: metric(6) } })
  },
  {
    name: 'client activity', component: ClientActivityReportComponent, url: '/api/reporting/analyst/client-activity',
    empty: clientActivity(),
    withData: clientActivity({ [SEP]: { month: SEP, activeClients: 3, newClients: 1, returningClients: 1, reactivatedClients: 1, lapsedClients: 0 } })
  },
  {
    name: 'retention', component: RetentionReportComponent, url: '/api/reporting/analyst/retention', empty: retention(),
    withData: retention({ [SEP]: 4 })
  },
  {
    name: 'activation', component: ActivationReportComponent, url: '/api/reporting/analyst/activation', empty: activation(),
    withData: activation({ [SEP]: { registered: 2 } })
  },
  {
    name: 'concentration', component: ConcentrationReportComponent, url: '/api/reporting/analyst/concentration',
    empty: concentration(),
    withData: concentration({ [SEP]: { month: SEP, tradingClients: 1, volume: 100,
      tiers: [{ topPct: 1, clients: 1, volume: 100, sharePct: 100 }] } })
  },
  {
    name: 'order sizes', component: OrderSizeReportComponent, url: '/api/reporting/analyst/order-sizes', empty: orderSizes(),
    withData: orderSizes({ [SEP]: { month: SEP, filledOrders: 1, meanValue: 500, medianValue: 500, bands: bands([1, 0, 0, 0, 0]) } })
  }
];

describe('Analyst reports', () => {
  let http: HttpTestingController;

  function create<T>(component: Type<T>): ComponentFixture<T> {
    TestBed.configureTestingModule({
      imports: [component],
      providers: [provideHttpClient(), provideHttpClientTesting(), ReportWindowState]
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(component);
    fixture.detectChanges();
    return fixture;
  }

  /** Creates the report and answers its request with body. */
  function show<T>(component: Type<T>, url: string, body: object): HTMLElement {
    const fixture = create(component);
    http.expectOne(url).flush(body);
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  const textOf = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const all = (el: ParentNode, selector: string) => Array.from(el.querySelectorAll(selector));

  afterEach(() => http.verify());

  describe.each(CASES)('$name', ({ component, url, empty, withData }) => {
    it('shows a loading panel until the report arrives, then a row per month, and passes on the months it covers', () => {
      const fixture = create(component);
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('.panel')!.getAttribute('aria-busy')).toBe('true');
      expect(TestBed.inject(ReportWindowState).label()).toBeNull();

      http.expectOne(url).flush(withData);
      fixture.detectChanges();

      expect(el.querySelector('.panel')!.getAttribute('aria-busy')).toBe('false');
      expect(all(el, 'tbody tr')).toHaveLength(12);
      // Named once, in the section heading, rather than in every panel.
      expect(TestBed.inject(ReportWindowState).label()).toBe('Oct 2025 – Sep 2026');
      expect(textOf(el.querySelector('.panel-description'))).not.toContain('2026');
      expect(el.querySelector('.panel-eyebrow')).toBeNull();
    });

    it('says so when there is nothing in the 12 months, rather than drawing empty rows', () => {
      const el = show(component, url, empty);

      expect(el.querySelector('table')).toBeNull();
      expect(textOf(el.querySelector('.panel-body .message'))).toContain('last 12 complete months');
    });

    it('offers to try again after a failure, and loads again when asked', () => {
      const fixture = create(component);
      const el: HTMLElement = fixture.nativeElement;
      http.expectOne(url).flush('down', { status: 503, statusText: 'Service Unavailable' });
      fixture.detectChanges();

      expect(el.querySelector('[role="alert"]')).not.toBeNull();
      (el.querySelector('[role="alert"] button') as HTMLButtonElement).click();
      fixture.detectChanges();
      http.expectOne(url).flush(withData);
      fixture.detectChanges();

      expect(el.querySelector('[role="alert"]')).toBeNull();
      expect(all(el, 'tbody tr')).toHaveLength(12);
    });
  });

  it('scorecard: one signed change per figure, on the month the toggle picks; only notable changes are coloured', () => {
    const fixture = create(ScorecardReportComponent);
    http.expectOne('/api/reporting/analyst/scorecard').flush(scorecard({
      [SEP]: { month: SEP, volume: metric(47_400_000, 36.9, 134.5), filledOrders: metric(953, 3.8, null),
        averageTradeValue: metric(49758.63, -0.8, -12), activeClients: metric(267, 0, 136.3), registrations: metric(58, 3.6, 70.6) }
    }));
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const september = () => all(el, 'tbody tr')[11];
    const cell = (metricName: string) => september().querySelector(`[data-metric="${metricName}"]`)!;
    const changeIn = (metricName: string) => cell(metricName).querySelector('.change')!;

    expect(textOf(september().querySelector('th'))).toBe('Sep 2026');
    expect(textOf(cell('volume').querySelector('.figure'))).toBe('$47.4M');
    expect(textOf(cell('averageTradeValue').querySelector('.figure'))).toBe('$49,758.63');
    expect(el.querySelector('[data-testid="compare-month"]')!.getAttribute('aria-pressed')).toBe('true');
    expect(textOf(changeIn('volume'))).toBe('+36.9%');
    expect(changeIn('volume').classList).toContain('gain');
    // Under 10% either way: shown, but not coloured.
    expect(textOf(changeIn('filledOrders'))).toBe('+3.8%');
    expect(changeIn('filledOrders').classList).toContain('flat');
    expect(textOf(changeIn('averageTradeValue'))).toBe('−0.8%');
    expect(changeIn('averageTradeValue').classList).toContain('flat');

    (el.querySelector('[data-testid="compare-year"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(el.querySelector('[data-testid="compare-year"]')!.getAttribute('aria-pressed')).toBe('true');
    expect(textOf(changeIn('volume'))).toBe('+134.5%');
    expect(textOf(changeIn('filledOrders'))).toBe('—');
    expect(textOf(changeIn('averageTradeValue'))).toBe('−12.0%');
    expect(changeIn('averageTradeValue').classList).toContain('loss');
    // Volume alone has a bar, and the busiest month's is full width.
    expect((cell('volume').querySelector('.bar-fill') as HTMLElement).style.width).toBe('100%');
    expect(cell('filledOrders').querySelector('.bar-fill')).toBeNull();
    // October had no filled orders, so no average.
    expect(textOf(all(el, 'tbody tr')[0].querySelector('[data-metric="averageTradeValue"] .figure'))).toBe('—');
  });

  it('client activity: a stacked bar of new, returning and reactivated, scaled to the busiest month', () => {
    const el = show(ClientActivityReportComponent, '/api/reporting/analyst/client-activity', clientActivity({
      '2026-08': { month: '2026-08', activeClients: 100, newClients: 50, returningClients: 25, reactivatedClients: 25, lapsedClients: 0 },
      [SEP]: { month: SEP, activeClients: 50, newClients: 10, returningClients: 30, reactivatedClients: 10, lapsedClients: 60 }
    }));
    const september = all(el, 'tbody tr')[11];
    const august = all(el, 'tbody tr')[10];

    expect(textOf(september.querySelector('[data-testid="active-clients"]'))).toBe('50');
    expect((september.querySelector('.stack') as HTMLElement).style.width).toBe('50%');
    expect(all(september, '.segment').map(s => (s as HTMLElement).style.width)).toEqual(['20%', '60%', '20%']);
    expect(all(september, '.segment').map(s => s.getAttribute('title'))).toEqual(['New: 10', 'Returning: 30', 'Reactivated: 10']);
    // Mean lapsed is 5 a month: September's 60 is marked, August's 0 isn't.
    expect(textOf(september.lastElementChild)).toBe('60, above the 12-month average');
    expect(september.lastElementChild!.classList).toContain('lapsed-high');
    expect(textOf(august.lastElementChild)).toBe('0');
    expect(august.lastElementChild!.classList).not.toContain('lapsed-high');
  });

  it('retention: a heat cell per month since sign-up, blank after the window ends, a dash for an empty cohort', () => {
    const el = show(RetentionReportComponent, '/api/reporting/analyst/retention',
      retention({ '2025-10': 40, [SEP]: 4 }, offset => (offset === 0 ? 75 : 30)));
    const rows = all(el, 'tbody tr');

    expect(all(el, 'thead th').map(textOf)).toEqual(['Signed up', 'Clients', ...Array.from({ length: 12 }, (_, i) => `M${i}`)]);
    expect(all(rows[0], '[data-testid="retention-cell"]').map(textOf)).toEqual(['75.0%', ...Array(11).fill('30.0%')]);
    expect(rows[0].querySelector('[data-testid="retention-cell"]')!.classList).toContain('strong');
    expect(rows[0].querySelectorAll('[data-testid="retention-cell"]')[1].classList).not.toContain('strong');
    expect(rows[0].querySelector('[data-testid="retention-cell"]')!.getAttribute('aria-label'))
      .toBe('Oct 2025 sign-ups, month 0: 75.0%, 30 of 40 clients');
    // September's cohort has only its sign-up month; the other 11 columns are blank.
    expect(rows[11].querySelectorAll('[data-testid="retention-cell"]')).toHaveLength(1);
    expect(rows[11].querySelectorAll('td.heat')).toHaveLength(12);
    // November: nobody signed up.
    expect(textOf(rows[1].querySelector('[data-testid="retention-cell"]'))).toBe('—');
  });

  it('retention: one cell in the tab order; arrow keys move between cells and light the row and column', () => {
    const fixture = create(RetentionReportComponent);
    http.expectOne('/api/reporting/analyst/retention').flush(retention({ '2025-10': 40, '2025-11': 20 }));
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const cellAt = (row: number, offset: number) => el.querySelector(`[data-cell="${row}-${offset}"]`) as HTMLElement;
    const press = (key: string) => {
      document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
      fixture.detectChanges();
    };

    expect(all(el, '[data-testid="retention-cell"][tabindex="0"]')).toEqual([cellAt(0, 0)]);
    cellAt(0, 0).focus();
    fixture.detectChanges();

    press('ArrowRight');
    expect(document.activeElement).toBe(cellAt(0, 1));
    press('ArrowDown');
    expect(document.activeElement).toBe(cellAt(1, 1));
    expect(cellAt(1, 1).getAttribute('tabindex')).toBe('0');
    expect(all(el, '[data-testid="retention-cell"][tabindex="0"]')).toHaveLength(1);
    // The lit row's label and the lit column's heading.
    expect(all(el, 'tbody tr')[1].querySelector('th')!.classList).toContain('lit');
    expect(all(el, 'thead th')[3].classList).toContain('lit');
    press('End');
    expect(document.activeElement).toBe(cellAt(1, 10));
    // November's cohort has no month 11: the triangle's blank corner is skipped.
    press('ArrowRight');
    expect(document.activeElement).toBe(cellAt(1, 10));
    press('Home');
    expect(document.activeElement).toBe(cellAt(1, 0));

    // At the left edge, ArrowLeft stays put; ArrowUp moves to the cohort above.
    press('ArrowLeft');
    expect(document.activeElement).toBe(cellAt(1, 0));
    press('ArrowUp');
    expect(document.activeElement).toBe(cellAt(0, 0));
    // At the top edge, ArrowUp stays put.
    press('ArrowUp');
    expect(document.activeElement).toBe(cellAt(0, 0));
    press('ArrowRight');
    press('ArrowLeft');
    expect(document.activeElement).toBe(cellAt(0, 0));

    // October's month 11 has nothing below it: November's cohort ends at month 10.
    press('End');
    expect(document.activeElement).toBe(cellAt(0, 11));
    press('ArrowDown');
    expect(document.activeElement).toBe(cellAt(0, 11));

    // Down month 0 to the last cohort, where ArrowDown stays put.
    press('Home');
    for (let i = 0; i < 11; i++) press('ArrowDown');
    expect(document.activeElement).toBe(cellAt(11, 0));
    press('ArrowDown');
    expect(document.activeElement).toBe(cellAt(11, 0));
  });

  it('retention: the arrow keys are kept from scrolling the page; any other key is left alone', () => {
    const fixture = create(RetentionReportComponent);
    http.expectOne('/api/reporting/analyst/retention').flush(retention({ '2025-10': 40 }));
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const first = el.querySelector('[data-cell="0-0"]') as HTMLElement;
    first.focus();
    const keydown = (key: string) => {
      const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      first.dispatchEvent(event);
      fixture.detectChanges();
      return event;
    };

    expect(keydown('a').defaultPrevented).toBe(false);
    expect(document.activeElement).toBe(first);
    expect(keydown('ArrowUp').defaultPrevented).toBe(true);
  });

  it('retention: hovering a cell lights its cohort and its month, and leaving the table clears them', () => {
    const fixture = create(RetentionReportComponent);
    http.expectOne('/api/reporting/analyst/retention').flush(retention({ '2025-10': 40, '2025-11': 20 }));
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const lit = () => all(el, '.lit').map(textOf);

    el.querySelector('[data-cell="1-2"]')!.dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();

    // November's label and client count, and the M2 heading.
    expect(lit()).toEqual(['M2', 'Nov 2025', '20']);
    expect(all(el, 'td.cross')).toContain(el.querySelector('[data-cell="0-2"]'));
    expect(all(el, 'td.cross')).toContain(el.querySelector('[data-cell="1-5"]'));
    expect(all(el, 'td.cross')).not.toContain(el.querySelector('[data-cell="0-5"]'));

    el.querySelector('table')!.dispatchEvent(new MouseEvent('mouseleave'));
    fixture.detectChanges();

    expect(lit()).toEqual([]);
    expect(all(el, 'td.cross')).toHaveLength(0);
  });

  it('before a report arrives, its scales and summaries are empty rather than failing', () => {
    const scorecardReport = create(ScorecardReportComponent).componentInstance;
    expect(scorecardReport.maxVolume()).toBe(0);
    http.expectOne('/api/reporting/analyst/scorecard').flush(scorecard());

    TestBed.resetTestingModule();
    const activity = create(ClientActivityReportComponent).componentInstance;
    expect(activity.maxActive()).toBe(0);
    expect(activity.meanLapsed()).toBe(0);
    http.expectOne('/api/reporting/analyst/client-activity').flush(clientActivity());

    TestBed.resetTestingModule();
    const retentionReport = create(RetentionReportComponent).componentInstance;
    expect(retentionReport.offsets()).toEqual([]);
    http.expectOne('/api/reporting/analyst/retention').flush(retention());

    TestBed.resetTestingModule();
    const activationReport = create(ActivationReportComponent).componentInstance;
    expect(activationReport.anyIncomplete()).toBe(false);
    http.expectOne('/api/reporting/analyst/activation').flush(activation());

    TestBed.resetTestingModule();
    const orderSizeReport = create(OrderSizeReportComponent).componentInstance;
    expect(orderSizeReport.bands()).toEqual([]);
    http.expectOne('/api/reporting/analyst/order-sizes').flush(orderSizes());
  });

  it('activation: percentages and median wait, with cohorts still in their first 30 days marked', () => {
    const el = show(ActivationReportComponent, '/api/reporting/analyst/activation', activation({
      '2026-08': { registered: 56, orderedWithin30Days: 48, orderedWithin30DaysPct: 85.7, orderedEver: 50, orderedEverPct: 89.3,
        medianDaysToFirstOrder: 3.6 },
      [SEP]: { registered: 58, orderedWithin30Days: 41, orderedWithin30DaysPct: 70.7, orderedEver: 41, orderedEverPct: 70.7,
        medianDaysToFirstOrder: 1 }
    }));
    const rows = all(el, 'tbody tr');

    expect(textOf(rows[10].querySelector('[data-testid="within-30-days"]'))).toBe('85.7%');
    expect(all(rows[10], 'td').map(textOf).slice(-2)).toEqual(['89.3%', '3.6 days']);
    expect(rows[10].classList).not.toContain('incomplete');
    expect(rows[11].classList).toContain('incomplete');
    expect(textOf(rows[11].querySelector('th'))).toBe('Sep 2026* (still inside its first 30 days)');
    expect(all(rows[11], 'td').map(textOf).slice(-1)).toEqual(['1.0 day']);
    expect(el.querySelector('[data-testid="activation-footnote"]')).not.toBeNull();
    // A month nobody signed up in has no figures.
    expect(all(rows[0], 'td').map(textOf)).toEqual(['0', '—', '—', '—']);
  });

  it('concentration: the top 1, 10 and 20 percent shares as bars; a dash in a month nobody traded', () => {
    const el = show(ConcentrationReportComponent, '/api/reporting/analyst/concentration', concentration({
      [SEP]: { month: SEP, tradingClients: 267, volume: 47_400_000, tiers: [
        { topPct: 1, clients: 3, volume: 5_780_000, sharePct: 12.2 },
        { topPct: 10, clients: 27, volume: 33_840_000, sharePct: 71.4 },
        { topPct: 20, clients: 54, volume: 44_270_000, sharePct: 93.4 }
      ] }
    }));
    const rows = all(el, 'tbody tr');

    expect(all(el, 'thead th').map(textOf)).toEqual(['Month', 'Volume', 'Share', 'Top 1%', 'Top 10%', 'Top 20%']);
    expect(all(rows[11], 'td').map(textOf)).toEqual(['$47.4M', '', '12.2%', '71.4%', '93.4%']);
    // One nested bar, widest slice first so each narrower one sits on top.
    expect(all(rows[11], '.nested .bar-fill').map(f => (f as HTMLElement).style.width)).toEqual(['93.4%', '71.4%', '12.2%']);
    expect(rows[11].querySelector('[data-tier="10"] span')!.getAttribute('title')).toBe('27 clients, $33.8M');
    expect(all(rows[0], 'td').map(textOf)).toEqual(['$0', '', '—', '—', '—']);
  });

  it('order sizes: a legend of bands and a stacked bar of each month\'s order mix, with mean and median', () => {
    const el = show(OrderSizeReportComponent, '/api/reporting/analyst/order-sizes', orderSizes({
      [SEP]: { month: SEP, filledOrders: 10, meanValue: 7737.56, medianValue: 4999.99, bands: bands([1, 4, 3, 2, 0]) }
    }));
    const september = all(el, 'tbody tr')[11];

    expect(all(el, '.legend li').map(textOf)).toEqual(['< $1K', '$1K–$5K', '$5K–$10K', '$10K–$25K', '$25K+']);
    expect(all(september, '.segment').map(s => (s as HTMLElement).style.width)).toEqual(['10%', '40%', '30%', '20%', '0%']);
    expect(textOf(september.querySelector('.sr-only'))).toBe('< $1K: 1, $1K–$5K: 4, $5K–$10K: 3, $10K–$25K: 2, $25K+: 0');
    expect(all(september, 'td').map(textOf).slice(-3)).toEqual(['10', '$7,737.56', '$4,999.99']);
    expect(all(all(el, 'tbody tr')[0], 'td').map(textOf).slice(-2)).toEqual(['—', '—']);
  });
});
