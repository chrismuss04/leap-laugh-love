import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ReportPanelComponent, ReportState } from './report-panel';
import { StatTileComponent } from './stat-tile';

@Component({
  imports: [ReportPanelComponent],
  template: `
    <app-report-panel eyebrow="Volume" heading="Trading volume" description="Filled orders over time."
                      source="reporting.orders" [scope]="scope()" [state]="state()" [message]="message()" (retry)="retries = retries + 1">
      <p class="report-body">The report</p>
    </app-report-panel>
  `
})
class HostComponent {
  readonly state = signal<ReportState>('pending');
  readonly message = signal('');
  readonly scope = signal('');
  retries = 0;
}

describe('ReportPanelComponent', () => {
  const render = (state: ReportState, message = '') => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.state.set(state);
    fixture.componentInstance.message.set(message);
    fixture.detectChanges();
    return fixture;
  };

  it('titles the report and labels its section by the title', () => {
    const el = render('pending').nativeElement as HTMLElement;
    const section = el.querySelector('section')!;
    const heading = el.querySelector('h2')!;
    expect(heading.textContent).toBe('Trading volume');
    expect(section.getAttribute('aria-labelledby')).toBe(heading.id);
    expect(el.textContent).toContain('Volume');
    expect(el.textContent).toContain('Filled orders over time.');
  });

  it('says what the report covers only when given a scope', () => {
    const fixture = render('ready');
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="panel-scope"]')).toBeNull();

    fixture.componentInstance.scope.set('All clients');
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="panel-scope"]')!.textContent).toBe('All clients');
  });

  it('shows a placeholder naming the data source until a report is built', () => {
    const el = render('pending').nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="report-pending"]')!.textContent).toContain('Coming soon');
    expect(el.querySelector('code')!.textContent).toBe('reporting.orders');
    expect(el.querySelector('.report-body')).toBeNull();
  });

  it('shows the report once ready', () => {
    const el = render('ready').nativeElement as HTMLElement;
    expect(el.querySelector('.report-body')!.textContent).toBe('The report');
    expect(el.querySelector('[data-testid="report-pending"]')).toBeNull();
  });

  it('is busy while loading', () => {
    const el = render('loading').nativeElement as HTMLElement;
    expect(el.querySelector('section')!.getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('.skeleton')).not.toBeNull();
  });

  it('says when there is nothing to report, in its own words or the default', () => {
    expect(render('empty').nativeElement.textContent).toContain('Nothing to report for these filters.');
    expect(render('empty', 'No trades this week.').nativeElement.textContent).toContain('No trades this week.');
  });

  it('offers a retry when the report fails', () => {
    const fixture = render('error');
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role="alert"]')!.textContent).toContain("Couldn't load this report.");
    el.querySelector('button')!.click();
    expect(fixture.componentInstance.retries).toBe(1);
  });
});

describe('StatTileComponent', () => {
  it('shows a dash until it has a value', () => {
    const fixture = TestBed.createComponent(StatTileComponent);
    fixture.componentRef.setInput('label', 'Trading volume');
    fixture.componentRef.setInput('hint', 'Value of filled orders');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.stat-label')!.textContent).toBe('Trading volume');
    expect(el.querySelector('.stat-value')!.textContent).toBe('—');
    expect(el.querySelector('.stat-hint')!.textContent).toBe('Value of filled orders');

    fixture.componentRef.setInput('value', '$1,204,880');
    fixture.detectChanges();
    expect(el.querySelector('.stat-value')!.textContent).toBe('$1,204,880');
  });

  it('shows a shimmer instead of the figure while loading', () => {
    const fixture = TestBed.createComponent(StatTileComponent);
    fixture.componentRef.setInput('label', 'Filled');
    fixture.componentRef.setInput('value', '12');
    fixture.componentRef.setInput('loading', true);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="stat-loading"]')).not.toBeNull();
    expect(el.textContent).not.toContain('12');

    fixture.componentRef.setInput('loading', false);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="stat-loading"]')).toBeNull();
    expect(el.querySelector('.stat-value')!.textContent).toBe('12');
  });

  it('shows how the figure moved, coloured by direction, only when given a trend', () => {
    const fixture = TestBed.createComponent(StatTileComponent);
    fixture.componentRef.setInput('label', 'Trading volume');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.stat-trend')).toBeNull();

    fixture.componentRef.setInput('trend', '−4.00% vs previous period');
    fixture.componentRef.setInput('trendDirection', 'loss');
    fixture.detectChanges();
    const trend = el.querySelector('.stat-trend')!;
    expect(trend.textContent).toBe('−4.00% vs previous period');
    expect(trend.classList.contains('text-loss')).toBe(true);
    expect(trend.classList.contains('text-gain')).toBe(false);

    // The colour itself, not just the class: the tile's default grey must not win.
    const el2 = fixture.nativeElement as HTMLElement;
    el2.style.setProperty('--loss', 'rgb(255, 0, 0)');
    el2.style.setProperty('--muted-foreground', 'rgb(128, 128, 128)');
    expect(getComputedStyle(trend).color).toBe('rgb(255, 0, 0)');
  });
});
