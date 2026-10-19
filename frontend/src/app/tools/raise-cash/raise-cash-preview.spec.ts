import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RaiseCashPreviewComponent } from './raise-cash-preview';
import { SellInput, SellPlan, planSells } from './sell-plan';

@Component({
  imports: [RaiseCashPreviewComponent],
  template: `
    <app-raise-cash-preview [plan]="plan()" [hasPlanned]="hasPlanned()" [inPlan]="inPlan()" (add)="added = added + 1">
    </app-raise-cash-preview>
  `
})
class HostComponent {
  readonly plan = signal<SellPlan>(planSells({ holdings: [], cash: 0, target: 0, strategy: 'even' }));
  readonly hasPlanned = signal(false);
  readonly inPlan = signal(false);
  added = 0;
}

describe('Raise Cash preview', () => {
  // AAPL bought at $100 now $150, MSFT at $500 now $400, with $1,000 cash.
  const base: SellInput = {
    holdings: [
      { symbol: 'AAPL', quantity: 10, price: 150, averageCost: 100 },
      { symbol: 'MSFT', quantity: 5, price: 400, averageCost: 500 }
    ],
    cash: 1000, target: 1000, strategy: 'winners'
  };

  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  const q = <T extends Element = HTMLElement>(selector: string) => (fixture.nativeElement as HTMLElement).querySelector<T>(selector);
  const testId = (id: string) => q(`[data-testid="${id}"]`);
  const text = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? null;
  const button = () => q<HTMLButtonElement>('[data-testid="add-to-plan"]')!;
  const rows = () => Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr'))
    .map(row => Array.from(row.children).map(cell => text(cell)));

  async function show(input: Partial<SellInput>, planned: { hasPlanned?: boolean; inPlan?: boolean } = {}): Promise<void> {
    host.plan.set(planSells({ ...base, ...input }));
    host.hasPlanned.set(planned.hasPlanned ?? false);
    host.inPlan.set(planned.inPlan ?? false);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(() => {
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
  });

  it('lists each sell with its shares, estimated proceeds and the gain or loss it realises', async () => {
    // Winners first: all 10 AAPL ($1,500, +$500), then 2 MSFT ($800, -$200).
    await show({ target: 3200 });
    expect(rows()).toEqual([
      ['Sell AAPL', '10', '$1,500.00', '+$500.00'],
      ['Sell MSFT', '2', '$800.00', '−$200.00']
    ]);
    expect(q('[data-sell="AAPL"] td:last-child')!.classList).toContain('gain');
    expect(q('[data-sell="MSFT"] td:last-child')!.classList).toContain('loss');
  });

  it('totals the cash raised, the new cash balance and the gain or loss realised', async () => {
    await show({ target: 3200 });
    expect(text(testId('cash-raised'))).toBe('$2,300.00');
    expect(text(testId('new-cash'))).toBe('$3,300.00');
    expect(text(testId('realised-total'))).toBe('+$300.00');
  });

  it('shows a dash for a sell with no average cost, and leaves it out of the total', async () => {
    await show({
      holdings: [{ symbol: 'AAPL', quantity: 10, price: 150, averageCost: null }, base.holdings[1]],
      target: 3200
    });
    // Unknown gains go last: all 5 MSFT (-$500), then 2 AAPL.
    expect(text(q('[data-sell="AAPL"] td:last-child'))).toBe('—');
    expect(text(testId('realised-total'))).toBe('−$500.00');
  });

  it('says there is nothing to sell, with Add to plan off, until the amount is above cash', async () => {
    await show({ target: 1000 });
    expect(testId('no-sells')).not.toBeNull();
    expect(q('table')).toBeNull();
    expect(button().disabled).toBe(true);
    expect(text(button())).toBe('Add to plan');
  });

  it('asks the tool to add the sells', async () => {
    await show({ target: 2000 });
    button().click();
    expect(host.added).toBe(1);
  });

  it('offers to update the plan, saying it replaces the Raise Cash trades already there', async () => {
    await show({ target: 2000 }, { hasPlanned: true });
    expect(text(button())).toBe('Update plan');
    expect(text(testId('replaces'))).toBe('Replaces the Raise Cash trades already in your plan.');
  });

  it('says when these sells are already in the plan', async () => {
    await show({ target: 2000 }, { hasPlanned: true, inPlan: true });
    expect(text(button())).toBe('In your plan');
    expect(button().disabled).toBe(true);
    expect(testId('replaces')).toBeNull();
  });
});
