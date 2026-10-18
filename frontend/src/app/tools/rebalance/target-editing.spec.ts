import { Component, inject, input } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Allocation, buildAllocation } from './allocation';
import { AllocationDonutComponent } from './allocation-donut';
import { TargetAllocation } from './target-allocation';
import { TargetTableComponent } from './target-table';

/** The donut and the table sharing one set of targets, as the Rebalance tool lays them out. */
@Component({
  selector: 'app-targets-host',
  imports: [AllocationDonutComponent, TargetTableComponent],
  providers: [TargetAllocation],
  template: `
    <app-allocation-donut [allocation]="ordered()" [targets]="targets"></app-allocation-donut>
    <app-target-table [allocation]="ordered()!" [targets]="targets"></app-target-table>
    <div data-testid="announcer">{{ targets.announcement() }}</div>
  `
})
class TargetsHostComponent {
  readonly targets = inject(TargetAllocation);
  readonly allocation = input.required<Allocation>();
  ordered(): Allocation | null {
    return this.targets.order(this.allocation());
  }
}

describe('Rebalance targets: donut and table', () => {
  let fixture: ComponentFixture<TargetsHostComponent>;
  let targets: TargetAllocation;
  const holding = (symbol: string, quantity: number) => ({ symbol, name: symbol, quantity, price: 100 });
  // AAPL 50%, MSFT 30%, cash 20% of $1,000.
  const allocation = () => buildAllocation([holding('AAPL', 5), holding('MSFT', 3)], 200, { alwaysCash: true });

  const el = (): HTMLElement => fixture.nativeElement;
  const q = <T extends Element = HTMLElement>(selector: string) => el().querySelector<T>(selector);
  const all = <T extends Element = HTMLElement>(selector: string) => Array.from(el().querySelectorAll<T>(selector));
  const donut = () => fixture.debugElement.query(By.directive(AllocationDonutComponent)).componentInstance as AllocationDonutComponent;
  const svg = () => q<SVGSVGElement>('app-allocation-donut svg')!;
  const handle = (boundary: number) => q<SVGGElement>(`.handle[data-boundary="${boundary}"]`)!;
  const input = (key: string) => q<HTMLInputElement>(`input[data-target-input="${key}"]`)!;
  const values = () => targets.keys().map((key, i) => [key, targets.targets()[i]]);
  const sum = () => targets.targets().reduce((a, b) => a + b, 0);

  async function settle(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await new Promise(resolve => requestAnimationFrame(resolve));
      fixture.detectChanges();
    }
    await new Promise(resolve => setTimeout(resolve, 600));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  /** Where a position (tenths clockwise from 12 o'clock) is on screen, on the target ring. */
  function pointAt(tenths: number): { clientX: number; clientY: number } {
    const { cx, cy, outer } = donut().geometry();
    const rect = svg().getBoundingClientRect();
    const angle = (tenths / 1000) * Math.PI * 2;
    return { clientX: rect.left + cx + Math.sin(angle) * (outer + 13), clientY: rect.top + cy - Math.cos(angle) * (outer + 13) };
  }

  function pointer(target: Element, type: string, tenths: number, init: PointerEventInit = {}): void {
    target.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerId: 1, pointerType: 'mouse', button: 0, ...pointAt(tenths), ...init }));
    fixture.detectChanges();
  }

  /** Presses on `from`, moves the pointer through each position, then lets go. */
  function drag(from: Element, path: number[], init: PointerEventInit = {}): void {
    pointer(from, 'pointerdown', path[0], init);
    for (const tenths of path) {
      pointer(svg(), 'pointermove', tenths, init);
    }
    pointer(svg(), 'pointerup', path[path.length - 1], init);
  }

  function key(target: Element, name: string, shiftKey = false): void {
    target.dispatchEvent(new KeyboardEvent('keydown', { key: name, shiftKey, bubbles: true }));
    fixture.detectChanges();
  }

  async function announced(): Promise<string> {
    await Promise.resolve();
    fixture.detectChanges();
    return q('[data-testid="announcer"]')!.textContent!.trim();
  }

  beforeEach(async () => {
    fixture = TestBed.createComponent(TargetsHostComponent);
    el().style.width = '900px';
    fixture.componentRef.setInput('allocation', allocation());
    targets = fixture.componentInstance.targets;
    targets.sync(allocation(), 'a1');
    fixture.detectChanges();
    await settle();
  });

  afterEach(() => fixture.destroy());

  describe('target ring', () => {
    it('starts at the current allocation, drawn as an outer ring with a handle on each boundary', () => {
      expect(values()).toEqual([['AAPL', 500], ['MSFT', 300], ['cash', 200]]);
      expect(all('path.target').map(p => p.getAttribute('data-target'))).toEqual(['AAPL', 'MSFT', 'cash']);
      expect(all('.handle')).toHaveLength(2);
      expect(handle(0).getAttribute('role')).toBe('slider');
      expect(handle(0).getAttribute('aria-valuenow')).toBe('50');
      expect(handle(0).getAttribute('aria-valuetext')).toBe('AAPL target 50%, MSFT target 30%');
    });

    it("follows the current allocation until a target is changed, then holds still", async () => {
      const moved = buildAllocation([holding('AAPL', 6), holding('MSFT', 3)], 200, { alwaysCash: true });
      targets.sync(moved, 'a1');
      expect(targets.targetOf('AAPL')).toBe(545);

      key(handle(0), 'ArrowRight');
      targets.sync(allocation(), 'a1');
      expect(targets.targetOf('AAPL')).toBe(555);
    });
  });

  describe('dragging', () => {
    it('grows one slice and shrinks its neighbour, keeping the sum at 100%', () => {
      drag(handle(0), [500, 560, 600]);
      expect(values()).toEqual([['AAPL', 600], ['MSFT', 200], ['cash', 200]]);
      expect(sum()).toBe(1000);
      expect(q('[data-testid="target-total"]')!.textContent).toBe('100%');
    });

    it('snaps to whole percentages, or 0.1% steps with Shift', () => {
      drag(handle(0), [500, 563.4]);
      expect(targets.targetOf('AAPL')).toBe(560);
      drag(handle(0), [560, 563.4], { shiftKey: true });
      expect(targets.targetOf('AAPL')).toBe(563);
    });

    it('drags a slice to 0%, shows it as a dashed outline, and drags it back out', () => {
      drag(handle(0), [500, 700, 900]);
      expect(targets.targetOf('MSFT')).toBe(0);
      const sold = q<SVGPathElement>('path.slice[data-key="MSFT"]')!;
      expect(sold.classList).toContain('sold');
      expect(getComputedStyle(sold).strokeDasharray).not.toBe('none');
      expect(q('[data-change="MSFT"]')!.textContent).toContain('Sell all');

      // From the outline, moving clockwise pulls MSFT back out against cash.
      drag(sold, [800, 850]);
      expect(values()).toEqual([['AAPL', 800], ['MSFT', 50], ['cash', 150]]);
      expect(sold.classList).not.toContain('sold');
    });

    it('drags the coincident handle of a 0% slice back from either side', () => {
      drag(handle(0), [500, 800]);
      expect(targets.targetOf('MSFT')).toBe(0);
      // Both handles now sit at 80%; moving anticlockwise grows MSFT out of AAPL.
      drag(handle(1), [800, 750]);
      expect(values()).toEqual([['AAPL', 750], ['MSFT', 50], ['cash', 200]]);
    });

    it('lets cash be dragged too, to keep some uninvested', () => {
      drag(handle(1), [800, 700]);
      expect(values()).toEqual([['AAPL', 500], ['MSFT', 200], ['cash', 300]]);
    });

    it("follows the pointer across 12 o'clock without jumping a whole turn", () => {
      // Dragging the last boundary to the seam takes cash to 0%, and no further.
      drag(handle(1), [800, 950, 999, 5]);
      expect(values()).toEqual([['AAPL', 500], ['MSFT', 500], ['cash', 0]]);
    });

    it('announces the result once the drag ends', async () => {
      drag(handle(0), [500, 600]);
      expect(await announced()).toBe('AAPL target 60%, MSFT target 20%');
    });

    it("keeps the page still while dragging on touch: handles don't pan", () => {
      expect(getComputedStyle(handle(0)).touchAction).toBe('none');
      drag(handle(0), [500, 600], { pointerType: 'touch' });
      expect(targets.targetOf('AAPL')).toBe(600);
    });
  });

  describe('locking', () => {
    it('keeps a locked slice at its target while a neighbouring boundary moves', () => {
      q<HTMLButtonElement>('tr[data-key="MSFT"] button.lock')!.click();
      fixture.detectChanges();
      expect(q('[data-locked="MSFT"]')).not.toBeNull();
      expect(input('MSFT').disabled).toBe(true);

      // AAPL|MSFT now trades AAPL against cash, carrying MSFT along.
      drag(handle(0), [500, 600]);
      expect(values()).toEqual([['AAPL', 600], ['MSFT', 300], ['cash', 100]]);
    });

    it('announces locking and unlocking', async () => {
      const lock = q<HTMLButtonElement>('tr[data-key="AAPL"] button.lock')!;
      lock.click();
      expect(await announced()).toBe('AAPL locked at 50%');
      expect(lock.getAttribute('aria-pressed')).toBe('true');
      lock.click();
      expect(await announced()).toBe('AAPL unlocked');
    });
  });

  describe('typed targets', () => {
    function type(key: string, text: string): void {
      input(key).value = text;
      input(key).dispatchEvent(new Event('change'));
      fixture.detectChanges();
    }

    it('updates the donut when a target is typed', async () => {
      type('AAPL', '40');
      expect(values()).toEqual([['AAPL', 400], ['MSFT', 360], ['cash', 240]]);
      expect(handle(0).getAttribute('aria-valuenow')).toBe('40');
      expect(q('[data-change="AAPL"]')!.textContent).toContain('$100.00');
      expect(await announced()).toBe('AAPL target 40%');
    });

    it('shows dragged targets in the table', () => {
      drag(handle(0), [500, 620]);
      expect(input('AAPL').value).toBe('62');
      expect(input('MSFT').value).toBe('18');
    });

    it('accepts tenths, and puts back a target that isn\'t a number', () => {
      type('MSFT', '12.5%');
      expect(targets.targetOf('MSFT')).toBe(125);
      expect(input('MSFT').value).toBe('12.5');
      type('MSFT', 'lots');
      expect(input('MSFT').value).toBe('12.5');
      expect(targets.targetOf('MSFT')).toBe(125);
    });

    it('caps a typed target at 100% less any locked targets', () => {
      q<HTMLButtonElement>('tr[data-key="cash"] button.lock')!.click();
      fixture.detectChanges();
      type('AAPL', '95');
      expect(input('AAPL').value).toBe('80');
      expect(values()).toEqual([['AAPL', 800], ['MSFT', 0], ['cash', 200]]);
    });
  });

  describe('shortcuts', () => {
    it('Reset returns every target to the current allocation', () => {
      const reset = q<HTMLButtonElement>('[data-testid="reset-targets"]')!;
      expect(reset.disabled).toBe(true);
      drag(handle(0), [500, 700]);
      expect(reset.disabled).toBe(false);
      reset.click();
      fixture.detectChanges();
      expect(values()).toEqual([['AAPL', 500], ['MSFT', 300], ['cash', 200]]);
      expect(reset.disabled).toBe(true);
    });

    it('Equal weight sets every holding, not cash, to the same target', () => {
      q<HTMLButtonElement>('[data-testid="equal-weight"]')!.click();
      fixture.detectChanges();
      expect(values()).toEqual([['AAPL', 400], ['MSFT', 400], ['cash', 200]]);
    });
  });

  describe('keyboard', () => {
    it('moves a focused boundary 1% with the arrow keys and 0.1% with Shift', () => {
      handle(0).focus();
      key(handle(0), 'ArrowRight');
      expect(targets.targetOf('AAPL')).toBe(510);
      key(handle(0), 'ArrowUp');
      expect(targets.targetOf('AAPL')).toBe(520);
      key(handle(0), 'ArrowLeft', true);
      expect(targets.targetOf('AAPL')).toBe(519);
      key(handle(0), 'ArrowDown', true);
      expect(values()).toEqual([['AAPL', 518], ['MSFT', 282], ['cash', 200]]);
    });

    it('makes every boundary focusable', () => {
      expect(all('.handle').map(h => h.getAttribute('tabindex'))).toEqual(['0', '0']);
    });

    it('announces each change, and when a boundary can go no further', async () => {
      key(handle(1), 'ArrowLeft');
      expect(await announced()).toBe('MSFT target 29%, Cash target 21%');
      for (let i = 0; i < 25; i++) {
        key(handle(1), 'ArrowRight');
      }
      expect(targets.targetOf('cash')).toBe(0);
      expect(await announced()).toBe('MSFT target 50%, Cash target 0%. Can’t move further.');
    });
  });
});
