import { Directive, ElementRef, OnDestroy, effect, inject, input } from '@angular/core';

/** How long a figure takes to count up. */
export const COUNT_UP_MS = 900;

/**
 * Counts a card's headline figure up from zero, easing out, so it lands as the card settles. It
 * always ends on the exact figure.
 *
 * Usage: `<div [appCountUp]="total" [countUpFormat]="formatMoney" [countUpDelay]="200"></div>`
 */
@Directive({
    selector: '[appCountUp]'
})
export class CountUpDirective implements OnDestroy {
  readonly appCountUp = input.required<number>();
  readonly countUpFormat = input.required<(value: number) => string>();
  /** Milliseconds to hold at zero first, to start with the element's entrance. */
  readonly countUpDelay = input(0);

  private readonly element: HTMLElement = inject(ElementRef).nativeElement;
  private frame = 0;

  constructor() {
    effect(() => this.run(this.appCountUp(), this.countUpFormat(), this.countUpDelay()));
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frame);
  }

  private run(to: number, format: (value: number) => string, delay: number): void {
    cancelAnimationFrame(this.frame);
    const start = performance.now() + delay;
    const step = (now: number) => {
      const progress = Math.min(1, Math.max(0, (now - start) / COUNT_UP_MS));
      const eased = 1 - Math.pow(1 - progress, 3);
      this.element.textContent = format(progress < 1 ? to * eased : to);
      if (progress < 1) {
        this.frame = requestAnimationFrame(step);
      }
    };
    step(performance.now());
  }
}
