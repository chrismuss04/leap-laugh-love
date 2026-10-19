import { Component, inject, input } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Allocation, buildAllocation } from './allocation';
import { AllocationDonutComponent } from './allocation-donut';
import { TargetAllocation } from './target-allocation';
import { SliceTrade } from './trade-preview';

/** The donut with targets and trades, as the Rebalance tool shows it. */
@Component({
  selector: 'app-donut-host',
  imports: [AllocationDonutComponent],
  providers: [TargetAllocation],
  template: `<app-allocation-donut [allocation]="ordered()" [targets]="withTargets() ? targets : null"
                                   [trades]="trades()"></app-allocation-donut>`
})
class DonutHostComponent {
  readonly targets = inject(TargetAllocation);
  readonly allocation = input.required<Allocation>();
  readonly trades = input<Record<string, SliceTrade> | null>(null);
  readonly withTargets = input(true);
  ordered(): Allocation | null {
    return this.targets.order(this.allocation());
  }
}

describe('AllocationDonutComponent interactions', () => {
  let fixture: ComponentFixture<DonutHostComponent>;
  const holding = (symbol: string, quantity: number) => ({ symbol, name: `${symbol} Corp`, quantity, price: 100 });
  // AAPL 50%, MSFT 30%, cash 20% of $1,000.
  const allocation = () => buildAllocation([holding('AAPL', 5), holding('MSFT', 3)], 200, { alwaysCash: true });

  const el = (): HTMLElement => fixture.nativeElement;
  const q = <T extends Element = HTMLElement>(selector: string) => el().querySelector<T>(selector);
  const donut = () => fixture.debugElement.query(By.directive(AllocationDonutComponent)).componentInstance as AllocationDonutComponent;
  const slice = (key: string) => q<SVGPathElement>(`path.slice[data-key="${key}"]`)!;
  const handle = (boundary: number) => q<SVGGElement>(`.handle[data-boundary="${boundary}"]`)!;
  const svg = () => q<SVGSVGElement>('app-allocation-donut svg')!;
  const targets = () => fixture.componentInstance.targets;

  async function settle(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await new Promise(resolve => requestAnimationFrame(resolve));
      fixture.detectChanges();
    }
    await new Promise(resolve => setTimeout(resolve, 600));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function render(options: { trades?: Record<string, SliceTrade>; withTargets?: boolean } = {}): Promise<void> {
    fixture = TestBed.createComponent(DonutHostComponent);
    el().style.width = '900px';
    fixture.componentRef.setInput('allocation', allocation());
    fixture.componentRef.setInput('trades', options.trades ?? null);
    fixture.componentRef.setInput('withTargets', options.withTargets ?? true);
    targets().sync(allocation(), 'a1');
    fixture.detectChanges();
    await settle();
  }

  function key(target: Element, name: string): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true });
    target.dispatchEvent(event);
    fixture.detectChanges();
    return event;
  }

  function pointer(target: Element, type: string, init: PointerEventInit = {}): void {
    target.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerId: 1, pointerType: 'mouse', button: 0, ...init }));
    fixture.detectChanges();
  }

  afterEach(() => fixture?.destroy());

  describe('moving between slices with the keyboard', () => {
    beforeEach(() => render({ withTargets: false }));

    it('wraps with the arrow keys, and jumps with Home and End', () => {
      slice('AAPL').focus();
      key(slice('AAPL'), 'ArrowUp');
      expect(document.activeElement).toBe(slice('cash'));
      key(slice('cash'), 'Home');
      expect(document.activeElement).toBe(slice('AAPL'));
      key(slice('AAPL'), 'End');
      expect(document.activeElement).toBe(slice('cash'));
      key(slice('cash'), 'ArrowDown');
      expect(document.activeElement).toBe(slice('AAPL'));
    });

    it('leaves other keys, and Escape with nothing pinned, to the page', () => {
      expect(key(slice('AAPL'), 'a').defaultPrevented).toBe(false);
      expect(key(slice('AAPL'), 'Escape').defaultPrevented).toBe(false);
    });

    it('pins and unpins a slice by clicking it, telling the page', () => {
      const pinned: (string | null)[] = [];
      donut().selectedChange.subscribe(key => pinned.push(key));
      slice('MSFT').dispatchEvent(new MouseEvent('click', { bubbles: true }));
      slice('MSFT').dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(pinned).toEqual(['MSFT', null]);
    });

    it('lets go of a pinned slice that has gone', async () => {
      slice('MSFT').dispatchEvent(new MouseEvent('click', { bubbles: true }));
      fixture.componentRef.setInput('allocation', buildAllocation([holding('AAPL', 5)], 200));
      fixture.detectChanges();
      await settle();
      expect(donut().selected()).toBeNull();
    });

    it('shows a dash for shares it has no count for', () => {
      expect(donut().formatShares(null)).toBe('—');
      expect(donut().formatShares(2.5)).toBe('2.5');
    });
  });

  describe('trades on the slices', () => {
    const trades: Record<string, SliceTrade> = {
      AAPL: { side: 'SELL', quantity: 2, value: 200 },
      MSFT: { side: 'BUY', quantity: 3, value: 300 }
    };

    it('badges each slice with its trade', async () => {
      await render({ trades });
      expect(q('[data-badge="AAPL"]')!.textContent!.trim()).toBe('↓2');
      expect(q('[data-badge="MSFT"]')!.textContent!.trim()).toBe('↑3');
      expect(q('[data-badge="MSFT"]')!.classList).toContain('buy');
    });

    it("puts each slice's trade in its details, or None", async () => {
      await render({ trades: { MSFT: trades['MSFT'] } });
      pointer(slice('MSFT'), 'pointerenter');
      expect(q('[data-testid="tip-trade"]')!.textContent).toBe('↑ Buy 3');
      pointer(slice('AAPL'), 'pointerenter');
      expect(q('[data-testid="tip-trade"]')!.textContent).toBe('None');
      expect(donut().tradeText({ side: 'SELL', quantity: 4, value: 400 })).toBe('↓ Sell 4');
    });

    it('shows the dollars to buy or sell when there are targets but no trades', async () => {
      await render();
      targets().type('AAPL', 300);
      fixture.detectChanges();
      pointer(slice('AAPL'), 'pointerenter');
      expect(q('[data-testid="tip-change"]')!.textContent).toBe('$200.00');
      expect(q('.tooltip dt:nth-of-type(1)')).not.toBeNull();
      expect(el().textContent).toContain('To sell');
    });
  });

  describe('drag guards', () => {
    beforeEach(() => render());

    it('ignores moves and releases from a pointer that never pressed a handle', () => {
      const before = targets().targets();
      pointer(svg(), 'pointermove', { clientX: 10, clientY: 10 });
      pointer(svg(), 'pointerup');
      expect(targets().targets()).toBe(before);
    });

    it('ignores a right-button press on a handle', () => {
      const before = targets().targets();
      pointer(handle(0), 'pointerdown', { button: 2 });
      pointer(svg(), 'pointermove', { clientX: 10, clientY: 10 });
      expect(targets().targets()).toBe(before);
    });

    it("ignores another pointer's release mid-drag", () => {
      pointer(handle(0), 'pointerdown');
      pointer(svg(), 'pointerup', { pointerId: 2 });
      expect(donut().dragging()).toBeNull();
      // The first pointer is still dragging, so its release ends the drag.
      pointer(svg(), 'pointerup');
    });

    it("doesn't announce a press that never moved", async () => {
      pointer(handle(0), 'pointerdown');
      pointer(svg(), 'pointerup');
      await Promise.resolve();
      expect(targets().announcement()).toBe('');
    });

    it("doesn't pin a slice with the click that ends a drag, or show details mid-drag", async () => {
      const { cx, cy, outer } = donut().geometry();
      const box = svg().getBoundingClientRect();
      pointer(handle(0), 'pointerdown');
      pointer(svg(), 'pointermove', { clientX: box.left + cx + outer, clientY: box.top + cy + outer });
      donut().hover('AAPL');
      expect(donut().hovered()).toBeNull();
      pointer(svg(), 'pointerup');
      slice('AAPL').dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(donut().selected()).toBeNull();
      await new Promise(resolve => setTimeout(resolve));
      slice('AAPL').dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(donut().selected()).toBe('AAPL');
    });

    it('leaves keys other than the arrows to the page on a handle', () => {
      expect(key(handle(0), 'Enter').defaultPrevented).toBe(false);
    });

    it('starts no drag from a slice it has no target for', () => {
      donut().startSoldDrag(new PointerEvent('pointerdown'), 'NOPE');
      pointer(svg(), 'pointermove', { clientX: 10, clientY: 10 });
      expect(donut().dragging()).toBeNull();
    });
  });

  it('colours a target with no slice to match in a neutral grey', async () => {
    await render();
    targets().add('NVDA', 'NVIDIA');
    fixture.detectChanges();
    expect(donut().targetArcs().find(arc => arc.key === 'NVDA')!.color).toBe('var(--muted-foreground)');
  });

  it('jumps straight to new sizes for users who prefer reduced motion', async () => {
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList);
    fixture = TestBed.createComponent(DonutHostComponent);
    el().style.width = '900px';
    fixture.componentRef.setInput('allocation', allocation());
    targets().sync(allocation(), 'a1');
    fixture.detectChanges();
    // No frames needed: the arcs are already where they belong.
    expect(donut().arcs().map(a => a.key)).toEqual(['AAPL', 'MSFT', 'cash']);
    expect(donut().arcs()[2].end).toBeCloseTo(Math.PI * 2, 6);
  });

  it('draws nothing for a slice worth nothing', async () => {
    fixture = TestBed.createComponent(DonutHostComponent);
    el().style.width = '900px';
    const noCash = buildAllocation([holding('AAPL', 5)], 0, { alwaysCash: true });
    fixture.componentRef.setInput('allocation', noCash);
    fixture.componentRef.setInput('withTargets', false);
    targets().sync(noCash, 'a1');
    fixture.detectChanges();
    await settle();
    expect(slice('cash').getAttribute('d')).toBe('');
    expect(slice('AAPL').getAttribute('d')).toMatch(/^M/);
  });
});
