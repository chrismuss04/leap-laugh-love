import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ChartLine, OrderChartComponent } from './order-chart';

describe('OrderChartComponent', () => {
  let fixture: ComponentFixture<OrderChartComponent>;
  let component: OrderChartComponent;

  const lines: ChartLine[] = [
    { id: 'market', label: 'Ask', value: 100, tone: 'muted', draggable: false },
    { id: 'limit', label: 'Limit', value: 98, tone: 'accent', draggable: true },
    { id: 'stop', label: 'Stop', value: 96, tone: 'warning', draggable: true }
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OrderChartComponent],
      providers: [provideZonelessChangeDetection()]
    }).compileComponents();
    fixture = TestBed.createComponent(OrderChartComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('points', [99, 100, 101, 100.5]);
    fixture.componentRef.setInput('lines', lines);
    fixture.detectChanges();
  });

  function handle(label: string): HTMLButtonElement {
    const found = Array.from<HTMLButtonElement>(fixture.nativeElement.querySelectorAll('.handle'))
      .find(h => h.getAttribute('aria-label') === `${label} price`);
    if (!found) throw new Error(`Missing handle: ${label}`);
    return found;
  }

  function pointer(type: string, clientY: number): PointerEvent {
    return new PointerEvent(type, { clientY, pointerId: 7, button: 0, bubbles: true });
  }

  it('draws the price history and a line for each price', () => {
    expect(fixture.nativeElement.querySelector('.series-line').getAttribute('points').split(' ').length).toBe(4);
    const placed = component.placed();
    expect(placed.map(line => line.id)).toEqual(['market', 'limit', 'stop']);
    // Higher prices sit nearer the top.
    expect(placed[0].y).toBeLessThan(placed[1].y);
    expect(placed[1].y).toBeLessThan(placed[2].y);
    placed.forEach(line => {
      expect(line.y).toBeGreaterThanOrEqual(0);
      expect(line.y).toBeLessThanOrEqual(100);
    });
  });

  it('lets the lines for a limit and a stop be dragged, but not the price line', () => {
    expect(fixture.nativeElement.querySelectorAll('.handle').length).toBe(2);
    expect(fixture.nativeElement.querySelector('.tag').textContent).toContain('Ask $100.00');
    expect(handle('Limit').getAttribute('aria-valuenow')).toBe('98.00');
    expect(handle('Limit').getAttribute('aria-valuetext')).toBe('Limit $98.00');
  });

  it('moves a line to where it is dragged, on the scale it started on', () => {
    const moves: { id: string; value: number }[] = [];
    component.lineMove.subscribe(move => moves.push(move));
    const { min, max } = component.domain();
    const area: HTMLElement = fixture.nativeElement.querySelector('.chart');
    const rect = area.getBoundingClientRect();

    handle('Limit').dispatchEvent(pointer('pointerdown', rect.top + 10));
    // Mid-height is the middle of the price scale.
    handle('Limit').dispatchEvent(pointer('pointermove', rect.top + rect.height / 2));
    expect(moves.length).toBe(1);
    expect(moves[0].id).toBe('limit');
    expect(moves[0].value).toBeCloseTo((min + max) / 2, 1);

    // The scale holds still while a line is dragged, even once it is the highest price.
    fixture.componentRef.setInput('lines', lines.map(line => line.id === 'limit' ? { ...line, value: 110 } : line));
    fixture.detectChanges();
    expect(component.domain()).toEqual({ min, max });

    handle('Limit').dispatchEvent(pointer('pointermove', rect.top - 500));
    expect(moves[1].value).toBeCloseTo(max, 1);
    handle('Limit').dispatchEvent(pointer('pointerup', rect.top));
    expect(component.dragging()).toBeNull();
    expect(component.domain().max).toBeGreaterThan(max);
  });

  it('ignores moves when nothing is being dragged', () => {
    const moves: unknown[] = [];
    component.lineMove.subscribe(move => moves.push(move));
    handle('Stop').dispatchEvent(pointer('pointermove', 50));
    expect(moves).toEqual([]);
  });

  it('moves a line from the keyboard: a cent, a dollar with Shift, a percent with Page keys', () => {
    const values: number[] = [];
    component.lineMove.subscribe(move => values.push(move.value));
    const press = (key: string, shiftKey = false) =>
      handle('Stop').dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true }));

    press('ArrowUp');
    press('ArrowDown');
    press('ArrowUp', true);
    press('ArrowDown', true);
    press('PageUp');
    press('PageDown');
    press('a');

    expect(values).toEqual([96.01, 95.99, 97, 95, 96.96, 95.04]);
  });

  it('draws closes in other sessions than the one under way fainter, keeping the line whole', () => {
    expect(component.segments().map(s => s.dimmed)).toEqual([false]);
    fixture.componentRef.setInput('sessions', ['PRE_MARKET', 'PRE_MARKET', 'REGULAR', 'AFTER_HOURS']);
    fixture.componentRef.setInput('highlight', 'REGULAR');
    fixture.detectChanges();
    const segments = component.segments();
    // Pre-market to the second close, regular to the third, after-hours to the last.
    expect(segments.map(s => [s.dimmed, s.points.split(' ').length])).toEqual([[true, 2], [false, 2], [true, 2]]);
    expect(segments[0].points.split(' ')[1]).toBe(segments[1].points.split(' ')[0]);
    expect(fixture.nativeElement.querySelectorAll('.series-line.dimmed').length).toBe(2);

    // After the close, after-hours is the one at full strength.
    fixture.componentRef.setInput('highlight', 'AFTER_HOURS');
    fixture.detectChanges();
    expect(component.segments().map(s => [s.dimmed, s.points.split(' ').length])).toEqual([[true, 3], [false, 2]]);
  });

  it('says so when there is no history to draw', () => {
    fixture.componentRef.setInput('points', []);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.series-line')).toBeNull();
    expect(fixture.nativeElement.querySelector('.empty').textContent).toContain('isn’t available');
  });

  it('keeps a flat price from being stretched to fill the chart', () => {
    fixture.componentRef.setInput('points', [100, 100, 100]);
    fixture.componentRef.setInput('lines', []);
    fixture.detectChanges();
    const { min, max } = component.domain();
    expect(max - min).toBeGreaterThan(0.3);
  });
});
