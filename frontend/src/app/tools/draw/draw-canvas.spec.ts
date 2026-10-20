import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DrawCanvasComponent, LevelChange } from './draw-canvas';
import { TradeIdea } from './interpret';
import { DataPoint } from './stroke';

describe('DrawCanvasComponent', () => {
  let fixture: ComponentFixture<DrawCanvasComponent>;
  let canvas: DrawCanvasComponent;
  let drawn: DataPoint[][];
  let changes: LevelChange[];
  let cleared: number;

  const host = () => fixture.nativeElement as HTMLElement;
  const q = <T extends Element = HTMLElement>(selector: string) => host().querySelector<T>(selector);
  const frames = () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));

  /** Waits for the ResizeObserver to size the chart, then renders. */
  async function sized(): Promise<void> {
    for (let i = 0; i < 20 && !canvas.frame(); i++) {
      await frames();
      fixture.detectChanges();
    }
    fixture.detectChanges();
  }

  function pointer(type: string, x: number, y: number, target: Element = q('[data-testid="draw-surface"]')!): void {
    const rect = host().getBoundingClientRect();
    target.dispatchEvent(new PointerEvent(type, {
      bubbles: true, cancelable: true, pointerId: 1, button: 0, clientX: rect.left + x, clientY: rect.top + y
    }));
    fixture.detectChanges();
  }

  const idea = (overrides: Partial<TradeIdea> = {}): TradeIdea => ({
    shape: 'rally', side: 'BUY', entryType: 'MARKET', entry: 100, entryStep: 0, target: 108, targetStep: 30,
    stop: 96, needsShares: false, ...overrides
  });

  beforeEach(async () => {
    fixture = TestBed.createComponent(DrawCanvasComponent);
    canvas = fixture.componentInstance;
    host().style.width = '640px';
    drawn = [];
    changes = [];
    cleared = 0;
    canvas.drawn.subscribe(line => drawn.push(line));
    canvas.levelChange.subscribe(change => changes.push(change));
    canvas.cleared.subscribe(() => cleared++);
    fixture.componentRef.setInput('symbol', 'AAPL');
    fixture.componentRef.setInput('history', [98, 99, 101, 100, 102, 99]);
    fixture.componentRef.setInput('price', 100);
    fixture.componentRef.setInput('futureSteps', 60);
    await sized();
  });

  it('draws the history up to a live dot, and invites a drawing', () => {
    expect(q('.history-line')!.getAttribute('d')).toMatch(/^M0\.0,/);
    const dot = q('.now-dot')!;
    expect(Number(dot.getAttribute('cx'))).toBeCloseTo(canvas.now().x);
    expect(q('[data-testid="draw-hint"]')!.textContent).toContain('AAPL');
    expect(q('.ghost')).not.toBeNull();
    expect(host().querySelectorAll('.axis:not(.time)').length).toBeGreaterThan(2);
  });

  it('turns a stroke from the live dot into a line, from the price drawn at', () => {
    const now = canvas.now();
    pointer('pointerdown', now.x, now.y);
    expect(canvas.drawing()).toBe(true);
    for (let i = 1; i <= 10; i++) {
      pointer('pointermove', now.x + i * 20, now.y - i * 8);
    }
    expect(q('[data-testid="hover-price"]')).not.toBeNull();
    pointer('pointerup', now.x + 200, now.y - 80);

    expect(canvas.drawing()).toBe(false);
    expect(q('[data-testid="hover-price"]')).toBeNull();
    expect(drawn.length).toBe(1);
    const line = drawn[0];
    expect(line[0]).toEqual({ step: 0, price: 100 });
    expect(line[line.length - 1].step).toBeGreaterThan(10);
    expect(line[line.length - 1].price).toBeGreaterThan(100);
    expect(q('.drawn')!.getAttribute('d')).toMatch(/^M/);
    expect(q('[data-testid="draw-hint"]')).toBeNull();
  });

  it('ignores a press on the history, and takes a tap as nothing drawn', () => {
    const now = canvas.now();
    pointer('pointerdown', 20, now.y);
    expect(canvas.drawing()).toBe(false);

    pointer('pointerdown', now.x, now.y);
    pointer('pointerup', now.x + 5, now.y);
    expect(drawn).toEqual([]);
    expect(cleared).toBe(1);
    expect(q('.drawn')).toBeNull();
  });

  it('places a line as if drawn, and clears it with Escape', () => {
    canvas.place([{ step: 0, price: 100 }, { step: 30, price: 104 }]);
    fixture.detectChanges();
    expect(drawn.length).toBe(1);
    expect(q('.drawn')).not.toBeNull();

    q('[data-testid="draw-surface"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(q('.drawn')).toBeNull();
    expect(cleared).toBe(1);
  });

  describe('levels', () => {
    const tag = (level: string) => q(`.level-tag[data-level="${level}"]`)!;

    it('draws a buy\'s take profit and stop loss, and its limit when it waits for one', () => {
      fixture.componentRef.setInput('idea', idea());
      fixture.detectChanges();
      expect(Array.from(host().querySelectorAll('.level-tag')).map(t => t.getAttribute('data-level'))).toEqual(['target', 'stop']);
      expect(tag('target').textContent).toContain('$108.00');
      expect(tag('stop').getAttribute('role')).toBe('slider');

      fixture.componentRef.setInput('idea', idea({ shape: 'dip-then-rally', entryType: 'LIMIT', entry: 97 }));
      fixture.detectChanges();
      expect(tag('entry').textContent).toContain('Buy limit');
    });

    it('shows where a sell\'s line ends without letting it drag', () => {
      fixture.componentRef.setInput('idea', idea({ shape: 'fall', side: 'SELL', target: 94, stop: null }));
      fixture.detectChanges();
      expect(tag('target').textContent).toContain('Line ends');
      expect(tag('target').getAttribute('role')).toBeNull();
    });

    it('draws nothing for a flat line', () => {
      fixture.componentRef.setInput('idea', idea({ shape: 'flat' }));
      fixture.detectChanges();
      expect(host().querySelectorAll('.level-tag').length).toBe(0);
    });

    it('moves a level with the arrow keys', () => {
      fixture.componentRef.setInput('idea', idea());
      fixture.detectChanges();
      tag('target').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
      tag('stop').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', shiftKey: true, bubbles: true }));
      expect(changes.map(c => c.level)).toEqual(['target', 'stop']);
      expect(changes[0].price).toBeGreaterThan(108);
      expect(changes[1].price).toBeLessThan(96);
    });

    it('drags a level by its tag without starting a drawing', () => {
      fixture.componentRef.setInput('idea', idea());
      fixture.detectChanges();
      const target = tag('target');
      const rect = target.getBoundingClientRect();
      const origin = host().getBoundingClientRect();
      const x = rect.left - origin.left + 10;
      pointer('pointerdown', x, rect.top - origin.top + 5, target);
      pointer('pointermove', x, rect.top - origin.top - 30, target);
      pointer('pointerup', x, rect.top - origin.top - 30, target);
      expect(canvas.drawing()).toBe(false);
      expect(changes.length).toBe(1);
      expect(changes[0]).toEqual({ level: 'target', price: expect.any(Number) });
      expect(changes[0].price).toBeGreaterThan(108);
    });
  });
});
