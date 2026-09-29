import {
  Component, ElementRef, Injector, OnDestroy, ViewEncapsulation, afterNextRender, computed, effect, inject, input,
  signal, untracked
} from '@angular/core';
import { formatMoney } from './format';

/**
 * Renders a live figure whose digits roll to their new value, odometer style, and briefly turns
 * green or red when it rises or falls. Only the digits that changed move.
 *
 *   <span class="price num" [appRollingNumber]="price"></span>
 *
 * At rest the figure is one plain text node, so the font kerns it like any other text - giving each
 * character its own box would lose kerning and open gaps around the separators. While a change
 * rolls, an overlay places a digit strip over each character at its measured position, then
 * steps aside. The overlay is drawn with pseudo-elements, so textContent, copy/paste and screen
 * readers only ever see the plain value. Skipped for users who prefer reduced motion.
 */
@Component({
  selector: '[appRollingNumber]',
  template: `<span class="roll-text">{{ text() }}</span><span class="roll-layer" aria-hidden="true"></span>`,
  host: {
    class: 'rolling-number',
    '[class.roll-up]': "tone() === 'up'",
    '[class.roll-down]': "tone() === 'down'"
  },
  // The overlay is built outside Angular's templates, so its styles can't be scoped to them.
  encapsulation: ViewEncapsulation.None,
  styles: [`
    .rolling-number { position: relative; transition: color 0.8s ease; }
    .rolling-number.roll-up { color: var(--gain); transition-duration: 0.15s; }
    .rolling-number.roll-down { color: var(--loss); transition-duration: 0.15s; }
    .rolling-number.rolling .roll-text { color: transparent; }
    .roll-layer { position: absolute; top: 0; left: 0; pointer-events: none; }
    .roll-cell { position: absolute; overflow-x: visible; overflow-y: clip; white-space: pre; line-height: var(--lh); }
    .roll-cell[data-c]::before { content: attr(data-c); content: attr(data-c) / ''; }
    .roll-strip {
      display: block;
      transform: translateY(calc(var(--to) * -1 * var(--lh)));
      animation: roll-digit 0.6s cubic-bezier(0.22, 1, 0.36, 1);
    }
    .roll-strip::before {
      content: '0\\A 1\\A 2\\A 3\\A 4\\A 5\\A 6\\A 7\\A 8\\A 9';
      content: '0\\A 1\\A 2\\A 3\\A 4\\A 5\\A 6\\A 7\\A 8\\A 9' / '';
    }
    @keyframes roll-digit {
      from { transform: translateY(calc(var(--from) * -1 * var(--lh))); }
    }
    @media (prefers-reduced-motion: reduce) {
      .rolling-number { transition: none; }
    }
  `]
})
export class RollingNumberComponent implements OnDestroy {
  private static readonly ROLL_MS = 650;
  private static readonly reducedMotion =
    typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

  readonly value = input<number | null | undefined>(null, { alias: 'appRollingNumber' });
  readonly format = input<(value: number | null | undefined) => string>(formatMoney);
  /** False while the figure is being scrubbed, so it follows the pointer instantly. */
  readonly animate = input(true);

  readonly tone = signal<'up' | 'down' | null>(null);
  readonly text = computed(() => this.format()(this.value()));

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly injector = inject(Injector);
  private previous: number | null | undefined;
  private previousText = '';
  private wasAnimated = true;
  private toneTimer?: ReturnType<typeof setTimeout>;
  private rollTimer?: ReturnType<typeof setTimeout>;

  constructor() {
    effect(() => {
      const current = this.value();
      const text = this.text();
      const animate = untracked(this.animate);
      const { previous, previousText, wasAnimated } = this;
      this.previous = current;
      this.previousText = text;
      this.wasAnimated = animate;
      // Animate only a visible change to a live figure, not the jump back from a scrubbed one.
      if (!animate || !wasAnimated || previous == null || current == null || previousText === text) {
        return;
      }
      this.tone.set(current > previous ? 'up' : 'down');
      clearTimeout(this.toneTimer);
      this.toneTimer = setTimeout(() => this.tone.set(null), 1_200);
      if (!RollingNumberComponent.reducedMotion) {
        afterNextRender(() => this.roll(previousText, text), { injector: this.injector });
      }
    });
  }

  ngOnDestroy(): void {
    clearTimeout(this.toneTimer);
    clearTimeout(this.rollTimer);
  }

  /** Lays a cell over each rendered character, rolling the digits that differ from `from`. */
  private roll(from: string, to: string): void {
    const node = this.host.querySelector('.roll-text')?.firstChild;
    const layer = this.host.querySelector<HTMLElement>('.roll-layer');
    if (!node || !layer || node.textContent !== to) {
      return; // Superseded by a newer value before it rendered.
    }
    const style = getComputedStyle(this.host);
    const lineHeight = parseFloat(style.lineHeight) || parseFloat(style.fontSize) * 1.2;
    const origin = layer.getBoundingClientRect();
    const range = document.createRange();
    const cells: HTMLElement[] = [];
    for (let i = 0; i < to.length; i++) {
      range.setStart(node, i);
      range.setEnd(node, i + 1);
      const rect = range.getBoundingClientRect();
      const cell = document.createElement('span');
      cell.className = 'roll-cell';
      // A glyph's box is centred on its line, so centre a line-height tall cell on it.
      cell.style.cssText = `left:${rect.left - origin.left}px;top:${rect.top + rect.height / 2 - lineHeight / 2 - origin.top}px;`
        + `width:${rect.width}px;height:${lineHeight}px`;
      const char = to[i];
      if (isDigit(char)) {
        // Align from the right, so a figure that gains a digit still rolls its cents in place.
        const old = from[from.length - to.length + i];
        const strip = document.createElement('span');
        strip.className = 'roll-strip';
        strip.style.setProperty('--from', isDigit(old) ? old : char);
        strip.style.setProperty('--to', char);
        cell.appendChild(strip);
      } else {
        cell.dataset['c'] = char;
      }
      cells.push(cell);
    }
    layer.style.setProperty('--lh', `${lineHeight}px`);
    layer.replaceChildren(...cells);
    this.host.classList.add('rolling');
    clearTimeout(this.rollTimer);
    this.rollTimer = setTimeout(() => {
      this.host.classList.remove('rolling');
      layer.replaceChildren();
    }, RollingNumberComponent.ROLL_MS);
  }
}

function isDigit(char: string | undefined): boolean {
  return char !== undefined && char >= '0' && char <= '9';
}
