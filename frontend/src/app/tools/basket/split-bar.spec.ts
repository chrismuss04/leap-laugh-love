import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SplitBarComponent, SplitSegment } from './split-bar';

describe('SplitBarComponent', () => {
  let fixture: ComponentFixture<SplitBarComponent>;
  let dragged: { divider: number; positionCents: number }[];
  let stepped: { divider: number; steps: number }[];

  const el = (): HTMLElement => fixture.nativeElement;
  const divider = (i: number) => el().querySelector<HTMLElement>(`[data-divider="${i}"]`)!;
  const segment = (symbol: string) => el().querySelector<HTMLElement>(`.segment[data-symbol="${symbol}"]`)!;

  function render(segments: SplitSegment[], disabled = false): void {
    fixture = TestBed.createComponent(SplitBarComponent);
    fixture.componentRef.setInput('segments', segments);
    fixture.componentRef.setInput('disabled', disabled);
    dragged = [];
    stepped = [];
    fixture.componentInstance.dragged.subscribe(e => dragged.push(e));
    fixture.componentInstance.stepped.subscribe(e => stepped.push(e));
    fixture.detectChanges();
  }

  const split = [{ symbol: 'AAPL', amountCents: 50000 }, { symbol: 'NVDA', amountCents: 30000 }, { symbol: 'F', amountCents: 20000 }];

  it('draws one segment per stock, sized by its share, named in the legend with its % and amount', () => {
    render(split);
    expect(segment('AAPL').style.width).toBe('50%');
    expect(segment('F').style.left).toBe('80%');
    expect(segment('AAPL').textContent!.trim()).toBe('');
    const legend = Array.from(el().querySelectorAll('[data-testid="split-legend"] li')).map(li => Array.from(li.querySelectorAll('span')).map(span => span.textContent!.trim()).filter(Boolean).join(' '));
    expect(legend).toEqual(['AAPL 50% $500.00', 'NVDA 30% $300.00', 'F 20% $200.00']);
  });

  it('puts a slider between each pair of stocks', () => {
    render(split);
    expect(el().querySelectorAll('[role="slider"]').length).toBe(2);
    expect(divider(0).style.left).toBe('50%');
    expect(divider(1).getAttribute('aria-valuetext')).toBe('NVDA 30%, F 20%');
    expect(divider(1).getAttribute('aria-valuenow')).toBe('80');
  });

  it('shows every stock evenly while there is nothing to split', () => {
    render(split.map(s => ({ ...s, amountCents: 0 })), true);
    expect(parseFloat(segment('NVDA').style.width)).toBeCloseTo(100 / 3, 2);
    expect(divider(0).getAttribute('aria-disabled')).toBe('true');
  });

  it('steps a divider 1% with the arrow keys', () => {
    render(split);
    divider(0).dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    divider(1).dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    expect(stepped).toEqual([{ divider: 0, steps: 1 }, { divider: 1, steps: -1 }]);
  });

  it('turns a drag into a point on the bar, in cents', () => {
    render(split);
    const bar = el().querySelector<HTMLElement>('.bar')!;
    bar.style.width = '400px';
    const left = bar.getBoundingClientRect().left;
    divider(0).dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1, clientX: left + 200 }));
    divider(0).dispatchEvent(new PointerEvent('pointermove', { bubbles: true, pointerId: 1, clientX: left + 300 }));
    divider(0).dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 1, clientX: left + 300 }));
    divider(0).dispatchEvent(new PointerEvent('pointermove', { bubbles: true, pointerId: 1, clientX: left + 100 }));
    expect(dragged).toEqual([{ divider: 0, positionCents: 75000 }]);
  });

  it('draws a tiny share wide enough to see and grab, labelled with its real share', () => {
    render([{ symbol: 'AAPL', amountCents: 98000 }, { symbol: 'NVDA', amountCents: 1000 }, { symbol: 'F', amountCents: 1000 }]);
    expect(parseFloat(segment('NVDA').style.width)).toBeGreaterThan(3.5);
    expect(el().querySelector('[data-testid="split-legend"] li[data-symbol="NVDA"]')!.textContent).toContain('1%');
    expect(divider(0).getAttribute('aria-valuenow')).toBe('98');
    expect(divider(0).getAttribute('aria-valuetext')).toBe('AAPL 98%, NVDA 1%');
  });

  it('keeps a dragged divider under the pointer where a segment is drawn wider than it is', () => {
    render([{ symbol: 'AAPL', amountCents: 98000 }, { symbol: 'NVDA', amountCents: 1000 }, { symbol: 'F', amountCents: 1000 }]);
    const bar = el().querySelector<HTMLElement>('.bar')!;
    bar.style.width = '400px';
    const nvda = segment('NVDA').getBoundingClientRect();
    // Halfway across NVDA's drawn segment is halfway across its real $10.
    divider(1).dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1 }));
    divider(1).dispatchEvent(new PointerEvent('pointermove', { bubbles: true, pointerId: 1, clientX: nvda.left + nvda.width / 2 }));
    expect(dragged[0].divider).toBe(1);
    expect(dragged[0].positionCents).toBeCloseTo(98500, -2);
  });

  it('ignores drags and keys while disabled', () => {
    render(split, true);
    divider(0).dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    divider(0).dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1 }));
    divider(0).dispatchEvent(new PointerEvent('pointermove', { bubbles: true, pointerId: 1 }));
    expect(stepped).toEqual([]);
    expect(dragged).toEqual([]);
  });
});
