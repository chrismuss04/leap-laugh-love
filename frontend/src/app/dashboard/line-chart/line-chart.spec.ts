import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ChartPoint, LineChartComponent } from './line-chart';

describe('LineChartComponent', () => {
  const points: ChartPoint[] = [
    { time: 1, value: 10 }, { time: 2, value: 20 }, { time: 3, value: 15 }, { time: 4, value: 30 }
  ];

  let fixture: ComponentFixture<LineChartComponent>;
  let chart: LineChartComponent;
  let scrubs: (number | null)[];

  function init(inputs: Partial<LineChartComponent> = {}, width = 300): void {
    fixture.componentRef.setInput('points', inputs.points ?? points);
    if (inputs.baseline !== undefined) {
      fixture.componentRef.setInput('baseline', inputs.baseline);
    }
    fixture.detectChanges();
    chart.width.set(width);
    chart.ngOnChanges();
    fixture.detectChanges();
  }

  const key = (name: string, shiftKey = false) => {
    const event = new KeyboardEvent('keydown', { key: name, shiftKey, cancelable: true });
    chart.onKey(event);
    return event;
  };

  beforeEach(() => {
    fixture = TestBed.createComponent(LineChartComponent);
    chart = fixture.componentInstance;
    scrubs = [];
    chart.scrub.subscribe(value => scrubs.push(value));
  });

  it('draws nothing without points or width', () => {
    fixture.detectChanges();
    expect(chart.linePath).toBe('');
    init({ points: [] });
    expect(chart.linePath).toBe('');
    expect(chart.baselineY).toBeNull();
  });

  it('builds a line, an area and a baseline', () => {
    init({ baseline: 12 });
    expect(chart.linePath.startsWith('M0.0,')).toBe(true);
    expect(chart.areaPath.endsWith('Z')).toBe(true);
    expect(chart.baselineY).not.toBeNull();
    expect(fixture.nativeElement.querySelector('svg')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.baseline')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.live .dot')).not.toBeNull();
  });

  it('draws a flat line for a single point', () => {
    init({ points: [{ time: 1, value: 5 }] });
    expect(chart.linePath).toContain('L300.0');
    expect(chart.x(0)).toBe(300);
  });

  it('scales values into the padded height', () => {
    init();
    expect(chart.y(30)).toBe(12);
    expect(chart.y(10)).toBe(chart.height - 12);
    expect(chart.x(0)).toBe(0);
    expect(chart.x(3)).toBe(300);
  });

  it('scrubs with the pointer, once per point', () => {
    init();
    vi.spyOn(chart['host'].nativeElement, 'getBoundingClientRect').mockReturnValue({ left: 0, width: 300 } as DOMRect);
    chart.onPointer({ clientX: 150 } as PointerEvent);
    chart.onPointer({ clientX: 151 } as PointerEvent);
    chart.onPointer({ clientX: 9999 } as PointerEvent);
    expect(scrubs).toEqual([2, 3]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.crosshair')).not.toBeNull();
  });

  it('ignores the pointer without points or width', () => {
    chart.onPointer({ clientX: 10 } as PointerEvent);
    init({ points: [] });
    chart.onPointer({ clientX: 10 } as PointerEvent);
    expect(scrubs).toEqual([]);
  });

  it('scrubs with the keyboard', () => {
    init();
    key('ArrowLeft');
    expect(scrubs).toEqual([2]);
    key('ArrowRight', true);
    key('ArrowLeft', true);
    key('Home');
    key('End');
    expect(scrubs).toEqual([2, 3, 0, 3]);
    expect(key('x').defaultPrevented).toBe(false);
    expect(key('ArrowRight').defaultPrevented).toBe(true);
  });

  it('clears the scrub on Escape and on leaving', () => {
    init();
    key('Home');
    key('Escape');
    chart.clear();
    expect(scrubs).toEqual([0, null]);
    expect(chart.active()).toBeNull();
  });

  it('ignores keys when there are no points', () => {
    init({ points: [] });
    key('Home');
    expect(scrubs).toEqual([]);
  });

  it('drops a scrub that no longer has a point', () => {
    init();
    key('End');
    fixture.componentRef.setInput('points', points.slice(0, 2));
    fixture.detectChanges();
    expect(chart.active()).toBeNull();
    expect(scrubs).toEqual([3, null]);
  });

  describe('sessions', () => {
    /** Five points, 75px apart: pre-market up to the third, then regular hours, then after-hours. */
    const day: ChartPoint[] = [
      { time: 1, value: 10, session: 'REGULAR' }, { time: 2, value: 12, session: 'PRE_MARKET' },
      { time: 3, value: 11, session: 'PRE_MARKET' }, { time: 4, value: 20, session: 'REGULAR' },
      { time: 5, value: 22, session: 'AFTER_HOURS' }
    ];
    const element = () => fixture.nativeElement as HTMLElement;
    const kinds = () => chart.stops.map(stop => stop.kind);

    it('draws the whole line at full strength when it is all in the highlighted session', () => {
      init();
      expect(chart.stops).toEqual([{ offset: 0, kind: 'bright' }, { offset: 1, kind: 'bright' }]);
      expect(chart.hasDimmed).toBe(false);
      expect(element().querySelector('.line')!.getAttribute('stroke')).toBe(`url(#${chart.uid}-stroke)`);
    });

    it('highlights regular hours at the end by default, tinting the rest and glinting into the highlight', () => {
      init({ points: [...day.slice(0, 4), { time: 5, value: 22, session: 'REGULAR' }] });
      // Pre-market to the third point (150px), then regular hours to the end, feathered 64px.
      expect(chart.stops).toEqual([
        { offset: 0, kind: 'dim' },
        { offset: 118 / 300, kind: 'dim' }, { offset: (150 + 64 / 6) / 300, kind: 'glint' },
        { offset: 182 / 300, kind: 'bright' },
        { offset: 1, kind: 'bright' }
      ]);
      // The fill fades without a glint.
      expect(chart.fadeStops.map(stop => stop.kind)).not.toContain('glint');
      expect(element().querySelector('.stroke-stop[data-kind="glint"]')).not.toBeNull();
      expect(element().querySelector('.area')!.getAttribute('mask')).toBe(`url(#${chart.uid}-mask)`);
      expect(element().querySelector('.chart')!.getAttribute('aria-label')).toContain('current session is highlighted');
    });

    it('highlights only the stretch at the end, not earlier ones in the same session', () => {
      // Regular hours come before after-hours, so they aren't the session under way.
      init({ points: day });
      expect(kinds()).toEqual(['dim', 'dim']);
      fixture.componentRef.setInput('highlight', 'PRE_MARKET');
      init({ points: day });
      expect(kinds()).toEqual(['dim', 'dim']);
    });

    it('highlights the session it is given: after the close, after-hours', () => {
      fixture.componentRef.setInput('highlight', 'AFTER_HOURS');
      init({ points: day });
      expect(kinds()).toEqual(['dim', 'dim', 'glint', 'bright', 'bright']);
      expect(element().querySelector('.live')!.classList).not.toContain('dimmed');
      key('ArrowLeft');
      fixture.detectChanges();
      expect(element().querySelector('.dot')!.classList).toContain('dimmed');
    });

    it('carries the last session on to the live value while the market is closed, and stills the dot', () => {
      fixture.componentRef.setInput('highlight', 'AFTER_HOURS');
      init({ points: [...day, { time: 6, value: 22, session: 'CLOSED' }] });
      const live = element().querySelector('.live')!;
      expect(live.classList).toContain('closed');
      expect(live.classList).not.toContain('dimmed');
      // After-hours runs on to the end, rather than fading out before the live value.
      expect(chart.stops.at(-1)!.kind).toBe('bright');
      expect(chart.stops.at(-2)!.kind).toBe('bright');
    });
  });

  describe('laid out by time', () => {
    // 100-200 spans the chart; the first point is before it, the live one after.
    const timed: ChartPoint[] = [
      { time: 50, value: 10 }, { time: 100, value: 12 }, { time: 150, value: 18 }, { time: 250, value: 16 }
    ];
    beforeEach(() => fixture.componentRef.setInput('domain', { start: 100, end: 200 }));

    it('places points by time, leaving out those before the span and holding a later one at its end', () => {
      init({ points: timed });
      expect(chart.x(1)).toBe(0);
      expect(chart.x(2)).toBe(150);
      expect(chart.x(3)).toBe(300);
      expect(chart.lastDrawn).toBe(3);
      expect(chart.linePath.startsWith('M0.0,')).toBe(true);
      expect(chart.linePath.match(/L/g)!.length).toBe(2);
    });

    it('lets a live value past the end take the end, rather than stand straight above the point there', () => {
      init({ points: [...timed.slice(0, 3), { time: 200, value: 17 }, { time: 260, value: 19 }] });
      expect(chart.lastDrawn).toBe(4);
      expect(chart.x(4)).toBe(300);
      expect(chart.linePath.match(/L300\.0,/g)!.length).toBe(1);
    });

    it('leaves the rest of a day still under way empty', () => {
      init({ points: timed.slice(0, 3) });
      expect(chart.areaPath).toContain('L150.0,');
      expect(chart.areaPath).not.toContain('L300.0,');
    });

    it('leads in from the last hour before the day, tinted, then highlights the day', () => {
      fixture.componentRef.setInput('domain', { start: 100, end: 200, leadInFrom: 60 });
      fixture.componentRef.setInput('highlight', 'PRE_MARKET');
      init({ points: [
        { time: 20, value: 10, session: 'REGULAR' }, { time: 60, value: 11, session: 'AFTER_HOURS' },
        { time: 80, value: 12, session: 'AFTER_HOURS' }, { time: 110, value: 13, session: 'PRE_MARKET' },
        { time: 150, value: 15, session: 'PRE_MARKET' }
      ] });
      // The baseline point before the lead-in isn't drawn; the lead-in fills the first 30px.
      expect(chart.x(1)).toBe(0);
      expect(chart.x(2)).toBe(15);
      expect(chart.x(3)).toBeCloseTo(30 + 27);
      expect(chart.linePath.startsWith('M0.0,')).toBe(true);
      expect(chart.stops[0].kind).toBe('dim');
      expect(chart.stops.at(-1)!.kind).toBe('bright');
      expect(chart.dimmed(2)).toBe(true);
      expect(chart.dimmed(3)).toBe(false);
    });

    it('scrubs only the points drawn, snapping to the nearest', () => {
      init({ points: timed.slice(0, 3) });
      vi.spyOn(chart['host'].nativeElement, 'getBoundingClientRect').mockReturnValue({ left: 0, width: 300 } as DOMRect);
      chart.onPointer({ clientX: 0 } as PointerEvent);
      chart.onPointer({ clientX: 290 } as PointerEvent);
      key('Home');
      key('ArrowLeft');
      key('End');
      expect(scrubs).toEqual([1, 2, 1, 2]);
    });
  });

  it('rebuilds when its host is resized, and stops observing on destroy', async () => {
    const host = fixture.nativeElement as HTMLElement;
    host.style.cssText = 'display:block;width:240px;height:100px';
    document.body.appendChild(host);
    fixture.componentRef.setInput('points', points);
    fixture.detectChanges();
    await new Promise(resolve => setTimeout(resolve, 100));
    expect(chart.width()).toBe(240);
    expect(chart.height).toBe(100);
    expect(chart.linePath).not.toBe('');
    fixture.destroy();
    host.remove();
  });
});
