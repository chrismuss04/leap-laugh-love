import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CashBarComponent } from './cash-bar';

/** The bar as a tool uses it: whatever it emits becomes its value. */
@Component({
  imports: [CashBarComponent],
  template: `
    <app-cash-bar [min]="min()" [limit]="limit()" [scaleMax]="scaleMax()" [value]="value()" [disabled]="disabled()"
                  (valueChange)="changed($event)"></app-cash-bar>
  `
})
class HostComponent {
  readonly min = signal(1000);
  readonly limit = signal(4000);
  readonly scaleMax = signal(5000);
  readonly value = signal(2000);
  readonly disabled = signal(false);
  readonly emitted: number[] = [];

  changed(value: number): void {
    this.emitted.push(value);
    this.value.set(value);
  }
}

describe('Raise Cash bar', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;

  const q = <T extends Element = HTMLElement>(selector: string) => (fixture.nativeElement as HTMLElement).querySelector<T>(selector);
  const handle = () => q('[data-testid="cash-handle"]')!;
  const track = () => q('[data-testid="cash-track"]')!;

  beforeEach(async () => {
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
  });

  function press(key: string, shiftKey = false): void {
    handle().dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true }));
    fixture.detectChanges();
  }

  /** The point on the track where an amount sits. */
  function yFor(amount: number): number {
    const rect = track().getBoundingClientRect();
    return rect.bottom - (amount / host.scaleMax()) * rect.height;
  }

  function pointer(type: string, amount: number, init: PointerEventInit = {}): void {
    const rect = track().getBoundingClientRect();
    track().dispatchEvent(new PointerEvent(type, {
      bubbles: true, cancelable: true, pointerId: 1, pointerType: 'mouse', button: 0,
      clientX: rect.left + rect.width / 2, clientY: yFor(amount), ...init
    }));
    fixture.detectChanges();
  }

  describe('keyboard', () => {
    const cases: [string, boolean, number][] = [
      ['ArrowUp', false, 2010], ['ArrowRight', false, 2010], ['ArrowUp', true, 2001],
      ['ArrowDown', false, 1990], ['ArrowLeft', false, 1990], ['ArrowDown', true, 1999],
      ['PageUp', false, 2100], ['PageDown', false, 1900], ['Home', false, 1000], ['End', false, 4000]
    ];
    for (const [key, shift, expected] of cases) {
      it(`${shift ? 'Shift+' : ''}${key} moves to ${expected}`, () => {
        press(key, shift);
        expect(host.emitted).toEqual([expected]);
      });
    }

    it('never goes below current cash', () => {
      host.value.set(1005);
      fixture.detectChanges();
      press('ArrowDown');
      press('PageDown');
      expect(host.emitted).toEqual([1000, 1000]);
    });

    it('asks past the limit, so the tool can stop there and say why', () => {
      host.value.set(4000);
      fixture.detectChanges();
      press('ArrowUp');
      expect(host.emitted).toEqual([4010]);
    });

    it('ignores other keys', () => {
      press('a');
      press('Enter');
      expect(host.emitted).toEqual([]);
    });
  });

  describe('dragging', () => {
    beforeEach(() => {
      // $500 over the bar, so a pixel is about a dollar.
      host.min.set(50);
      host.limit.set(400);
      host.scaleMax.set(500);
      host.value.set(50);
      fixture.detectChanges();
    });

    it('snaps to $10 steps', () => {
      pointer('pointerdown', 203);
      pointer('pointermove', 257);
      pointer('pointerup', 257);
      expect(host.emitted).toEqual([200, 260]);
    });

    it('snaps to $1 steps while Shift is held', () => {
      pointer('pointerdown', 203, { shiftKey: true });
      pointer('pointerup', 203, { shiftKey: true });
      expect(host.emitted).toEqual([203]);
    });

    it('never goes below current cash, but asks past the top so the tool can stop there and say why', () => {
      pointer('pointerdown', 10);
      pointer('pointermove', 900);
      pointer('pointerup', 900);
      expect(host.emitted).toEqual([50, 900]);
    });

    it('stops following the pointer once it is let go', () => {
      pointer('pointerdown', 200);
      pointer('pointerup', 200);
      pointer('pointermove', 300);
      expect(host.emitted).toEqual([200]);
    });

    it('moves focus to the handle, so the keys carry on from there', () => {
      pointer('pointerdown', 200);
      expect(document.activeElement).toBe(handle());
    });
  });

  describe('accessibility', () => {
    it('is a vertical slider from current cash to the most the unlocked holdings can raise', () => {
      const h = handle();
      expect(h.getAttribute('role')).toBe('slider');
      expect(h.getAttribute('aria-orientation')).toBe('vertical');
      expect(h.getAttribute('aria-label')).toBe('Target cash');
      expect([h.getAttribute('aria-valuemin'), h.getAttribute('aria-valuemax'), h.getAttribute('aria-valuenow')])
        .toEqual(['1000', '4000', '2000']);
      expect(h.getAttribute('aria-valuetext')).toBe('$2,000.00 target');
      expect(h.tabIndex).toBe(0);
    });

    it('sits the handle at the target, as a share of the bar', () => {
      expect(handle().style.bottom).toBe('40%');
    });
  });

  describe('out of reach', () => {
    it('hatches the part of the bar locked holdings would cover', () => {
      const zone = q('[data-testid="out-of-reach"]')!;
      expect([zone.style.bottom, zone.style.height]).toEqual(['80%', '20%']);
    });

    it('has no hatching when nothing is locked', () => {
      host.limit.set(5000);
      fixture.detectChanges();
      expect(q('[data-testid="out-of-reach"]')).toBeNull();
    });
  });

  describe('disabled', () => {
    beforeEach(() => {
      host.disabled.set(true);
      fixture.detectChanges();
    });

    it('cannot be focused, and says so', () => {
      expect(handle().hasAttribute('tabindex')).toBe(false);
      expect(handle().getAttribute('aria-disabled')).toBe('true');
    });

    it('ignores the keys and the pointer', () => {
      press('ArrowUp');
      pointer('pointerdown', 3000);
      expect(host.emitted).toEqual([]);
    });
  });
});
