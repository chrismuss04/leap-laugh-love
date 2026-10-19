import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { COUNT_UP_MS, CountUpDirective } from './count-up';

@Component({
    imports: [CountUpDirective],
    template: `<span [appCountUp]="value()" [countUpFormat]="format" [countUpDelay]="delay"></span>`
})
class HostComponent {
  readonly value = signal(1000);
  readonly format = (value: number) => `$${Math.round(value)}`;
  delay = 0;
}

describe('CountUpDirective', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance'] });
  });
  afterEach(() => vi.useRealTimers());

  function create(delay = 0) {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.delay = delay;
    fixture.detectChanges();
    const text = () => (fixture.nativeElement as HTMLElement).textContent;
    return { fixture, text };
  }

  it('counts up from zero, easing out, and lands on the exact figure', () => {
    const { text } = create();
    expect(text()).toBe('$0');

    vi.advanceTimersByTime(COUNT_UP_MS / 2);
    // Ease-out: past halfway by half time.
    const midway = Number(text()!.slice(1));
    expect(midway).toBeGreaterThan(500);
    expect(midway).toBeLessThan(1000);

    vi.advanceTimersByTime(COUNT_UP_MS);
    expect(text()).toBe('$1000');
  });

  it('holds at zero through its delay', () => {
    const { text } = create(300);
    vi.advanceTimersByTime(250);
    expect(text()).toBe('$0');
    vi.advanceTimersByTime(300 + COUNT_UP_MS);
    expect(text()).toBe('$1000');
  });

  it('counts again to a new figure', () => {
    const { fixture, text } = create();
    vi.advanceTimersByTime(COUNT_UP_MS + 50);
    fixture.componentInstance.value.set(250);
    fixture.detectChanges();
    vi.advanceTimersByTime(COUNT_UP_MS + 50);
    expect(text()).toBe('$250');
  });

  it('counts with reduced motion too', () => {
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList);
    const { text } = create();
    expect(text()).toBe('$0');
    vi.advanceTimersByTime(COUNT_UP_MS + 50);
    expect(text()).toBe('$1000');
  });
});
