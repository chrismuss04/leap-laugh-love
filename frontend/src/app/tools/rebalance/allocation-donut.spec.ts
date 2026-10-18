import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AllocationHolding, buildAllocation } from './allocation';
import { AllocationDonutComponent } from './allocation-donut';

describe('AllocationDonutComponent', () => {
  let fixture: ComponentFixture<AllocationDonutComponent>;
  const holding = (symbol: string, quantity: number, price: number | null): AllocationHolding =>
    ({ symbol, name: `${symbol} Corp`, quantity, price });

  const el = (): HTMLElement => fixture.nativeElement;
  const q = <T extends Element = HTMLElement>(selector: string) => el().querySelector<T>(selector);
  const all = <T extends Element = HTMLElement>(selector: string) => Array.from(el().querySelectorAll<T>(selector));
  const slice = (key: string) => q<SVGPathElement>(`path.slice[data-key="${key}"]`)!;
  const labels = () => all<SVGGElement>('g.label')
    .map(g => Array.from(g.querySelectorAll('tspan')).map(t => t.textContent!.trim()).join(' '));
  const frame = () => new Promise(resolve => requestAnimationFrame(() => resolve(null)));

  /** Lets the ResizeObserver size the chart and the slices finish easing in. */
  async function settle(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await frame();
      fixture.detectChanges();
    }
    await new Promise(resolve => setTimeout(resolve, 600));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function render(holdings: AllocationHolding[], cash: number, state: 'loading' | 'error' | 'ready' = 'ready') {
    fixture = TestBed.createComponent(AllocationDonutComponent);
    el().style.width = '900px';
    fixture.componentRef.setInput('allocation', buildAllocation(holdings, cash));
    fixture.componentRef.setInput('state', state);
    fixture.detectChanges();
    await settle();
  }

  async function update(holdings: AllocationHolding[], cash: number) {
    fixture.componentRef.setInput('allocation', buildAllocation(holdings, cash));
    fixture.detectChanges();
    await settle();
  }

  afterEach(() => fixture?.destroy());

  describe('display', () => {
    it('draws one slice per holding plus a grey cash slice', async () => {
      await render([holding('AAPL', 10, 150), holding('MSFT', 2, 400)], 700);
      expect(all('path.slice').map(p => p.getAttribute('data-key'))).toEqual(['AAPL', 'MSFT', 'cash']);
      expect(slice('cash').getAttribute('fill')).toBe('#8b8b99');
      expect(slice('AAPL').getAttribute('d')).toMatch(/^M/);
    });

    it('labels every slice with its symbol and percentage', async () => {
      await render([holding('AAPL', 10, 150), holding('MSFT', 2, 400)], 700);
      expect(labels()).toEqual(expect.arrayContaining(['AAPL 50%', 'MSFT 27%', 'Cash 23%']));
    });

    it("groups the labels of slices under 3% into one 'Other', keeping each slice selectable", async () => {
      await render([holding('AAPL', 95, 100), holding('X', 2, 100), holding('Y', 2, 100), holding('Z', 1, 100)], 0);
      expect(labels()).toEqual(['AAPL 95%', 'Other 5.0%']);
      expect(all('path.slice[role="option"]')).toHaveLength(4);

      slice('Y').dispatchEvent(new PointerEvent('pointerenter'));
      fixture.detectChanges();
      expect(q('[data-testid="donut-tooltip"]')!.textContent).toContain('Y');
      expect(q('[data-testid="tip-share"]')!.textContent).toBe('2.0%');
      expect(q('[data-testid="legend-other"]')!.textContent).toContain('5.0%');
    });

    it("shows the account's total in the centre", async () => {
      await render([holding('AAPL', 10, 150)], 1000);
      expect(q('[data-testid="donut-total"]')!.textContent).toContain('$2,500.00');
      expect(q('.centre-caption')!.textContent).toContain('1 holding + cash');
    });

    it('updates the slices, labels and total as live prices move', async () => {
      await render([holding('AAPL', 10, 100), holding('MSFT', 10, 100)], 0);
      expect(labels()).toEqual(expect.arrayContaining(['AAPL 50%', 'MSFT 50%']));
      const before = slice('AAPL').getAttribute('d');

      await update([holding('AAPL', 10, 300), holding('MSFT', 10, 100)], 0);
      expect(labels()).toEqual(expect.arrayContaining(['AAPL 75%', 'MSFT 25%']));
      expect(slice('AAPL').getAttribute('d')).not.toBe(before);
      expect(q('[data-testid="donut-total"]')!.textContent).toContain('$4,000.00');
    });
  });

  describe('details on hover and focus', () => {
    beforeEach(() => render([holding('AAPL', 10, 150), holding('MSFT', 2, 400)], 700));

    it('shows symbol, value, shares and percentage when a slice is hovered', () => {
      slice('AAPL').dispatchEvent(new PointerEvent('pointerenter'));
      fixture.detectChanges();
      const tip = q('[data-testid="donut-tooltip"]')!;
      expect(tip.textContent).toContain('AAPL');
      expect(q('[data-testid="tip-value"]')!.textContent).toBe('$1,500.00');
      expect(q('[data-testid="tip-shares"]')!.textContent).toBe('10');
      expect(q('[data-testid="tip-share"]')!.textContent).toBe('50%');

      q('.chart')!.dispatchEvent(new PointerEvent('pointerleave'));
      fixture.detectChanges();
      expect(q('[data-testid="donut-tooltip"]')).toBeNull();
    });

    it('shows the same details when a slice is focused, and leaves out shares for cash', () => {
      slice('cash').focus();
      fixture.detectChanges();
      expect(q('[data-testid="tip-value"]')!.textContent).toBe('$700.00');
      expect(q('[data-testid="tip-shares"]')).toBeNull();
    });

    it('describes each slice to screen readers', () => {
      expect(slice('AAPL').getAttribute('aria-label')).toBe('AAPL, AAPL Corp, $1,500.00, 10 shares, 50% of account');
      expect(slice('cash').getAttribute('aria-label')).toBe('Cash, $700.00, 23% of account');
    });

    it('is one tab stop; arrow keys move between slices and Enter pins one', () => {
      expect(all('path.slice').map(p => p.getAttribute('tabindex'))).toEqual(['0', '-1', '-1']);
      slice('AAPL').focus();
      slice('AAPL').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      fixture.detectChanges();
      expect(document.activeElement).toBe(slice('MSFT'));
      expect(q('[data-testid="donut-tooltip"]')!.textContent).toContain('MSFT');

      slice('MSFT').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      fixture.detectChanges();
      expect(slice('MSFT').getAttribute('aria-selected')).toBe('true');
      expect(q('.legend-row[data-key="MSFT"]')!.getAttribute('aria-pressed')).toBe('true');

      slice('MSFT').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      fixture.detectChanges();
      expect(slice('MSFT').getAttribute('aria-selected')).toBe('false');
    });

    it('highlights the slice when its legend row is hovered', () => {
      q('.legend-row[data-key="MSFT"]')!.dispatchEvent(new PointerEvent('pointerenter'));
      fixture.detectChanges();
      expect(q('.slice-wrap.active path')!.getAttribute('data-key')).toBe('MSFT');
      expect(q('[data-testid="donut-tooltip"]')!.textContent).toContain('MSFT');
    });
  });

  describe('states', () => {
    it('shows only the cash slice and a prompt to add a holding when nothing is held', async () => {
      await render([], 2500);
      expect(all('path.slice').map(p => p.getAttribute('data-key'))).toEqual(['cash']);
      expect(labels()).toEqual(['Cash 100%']);
      expect(q('[data-testid="donut-empty"]')!.textContent).toContain('Add a holding');

      const added = vi.fn();
      fixture.componentInstance.addHolding.subscribe(added);
      q<HTMLButtonElement>('[data-testid="add-holding"]')!.click();
      expect(added).toHaveBeenCalled();
    });

    it('shows a skeleton ring while loading', async () => {
      await render([], 0, 'loading');
      expect(q('[data-testid="donut-loading"] .ring.skeleton')).not.toBeNull();
      expect(q('path.slice')).toBeNull();
    });

    it('shows an error with Retry when loading failed', async () => {
      await render([], 0, 'error');
      fixture.componentRef.setInput('errorMessage', 'Service unavailable');
      fixture.detectChanges();
      expect(q('[data-testid="donut-error"]')!.textContent).toContain('Service unavailable');

      const retried = vi.fn();
      fixture.componentInstance.retry.subscribe(retried);
      q<HTMLButtonElement>('[data-testid="donut-retry"]')!.click();
      expect(retried).toHaveBeenCalled();
    });

    it('notes holdings still waiting for a price', async () => {
      await render([holding('AAPL', 10, 150), holding('NEW', 1, null)], 0);
      expect(q('[data-testid="donut-unpriced"]')!.textContent).toContain('NEW');
    });
  });
});
