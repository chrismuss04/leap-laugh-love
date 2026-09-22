import { Directive, ElementRef, Input, OnChanges, OnDestroy, Renderer2, SimpleChanges } from '@angular/core';

/**
 * Briefly tints an element green or red when the bound number rises or falls, so a live tick is
 * noticeable without the figure itself moving. Skipped for users who prefer reduced motion.
 *
 *   <span [appFlash]="price">{{ price }}</span>
 */
@Directive({
  selector: '[appFlash]',
  standalone: true
})
export class FlashDirective implements OnChanges, OnDestroy {
  @Input('appFlash') value: number | null | undefined;

  private timer?: ReturnType<typeof setTimeout>;
  private static readonly reducedMotion =
    typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

  constructor(private el: ElementRef<HTMLElement>, private renderer: Renderer2) {}

  ngOnChanges(changes: SimpleChanges): void {
    const change = changes['value'];
    if (!change || change.firstChange || FlashDirective.reducedMotion) {
      return;
    }
    const previous = change.previousValue as number | null | undefined;
    const current = change.currentValue as number | null | undefined;
    if (previous == null || current == null || previous === current) {
      return;
    }
    const element = this.el.nativeElement;
    this.renderer.removeClass(element, 'flash-up');
    this.renderer.removeClass(element, 'flash-down');
    // Force a reflow so re-adding the same class restarts its animation.
    void element.offsetWidth;
    this.renderer.addClass(element, current > previous ? 'flash-up' : 'flash-down');
    clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.renderer.removeClass(element, 'flash-up');
      this.renderer.removeClass(element, 'flash-down');
    }, 700);
  }

  ngOnDestroy(): void {
    clearTimeout(this.timer);
  }
}
