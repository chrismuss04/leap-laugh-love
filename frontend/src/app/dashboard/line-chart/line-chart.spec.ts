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
    expect(fixture.nativeElement.querySelector('.dot.live')).not.toBeNull();
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
    /** Five points, 75px apart: the first two in pre-market, then regular hours, then after-hours. */
    const day: ChartPoint[] = [
      { time: 1, value: 10, session: 'REGULAR' }, { time: 2, value: 12, session: 'PRE_MARKET' },
      { time: 3, value: 11, session: 'PRE_MARKET' }, { time: 4, value: 20, session: 'REGULAR' },
      { time: 5, value: 22, session: 'AFTER_HOURS' }
    ];
    const element = () => fixture.nativeElement as HTMLElement;

    it('draws regular hours alone when no point is outside them', () => {
      init();
      expect(chart.bands).toEqual([]);
      expect(chart.regularSpans).toEqual([{ x: 0, width: 300 }]);
      expect(element().querySelector('.line.outside')).toBeNull();
      expect(element().querySelector('.band')).toBeNull();
    });

    it('bands each stretch outside regular hours, from the point before it', () => {
      init({ points: day });
      // Points 1-2 close 0-150px; point 4 closes 225-300px.
      expect(chart.bands).toEqual([
        { x: 0, width: 150, label: 'Pre-market' },
        { x: 225, width: 75, label: null }
      ]);
      expect(chart.regularSpans).toEqual([{ x: 150, width: 75 }]);
      expect(element().querySelectorAll('.band').length).toBe(2);
      expect(element().querySelector('.band-label')!.textContent!.trim()).toBe('Pre-market');
      expect(element().querySelector('.line.outside')!.getAttribute('clip-path')).toBe(`url(#${chart.uid}-outside)`);
      expect(element().querySelector('.area')!.getAttribute('clip-path')).toBe(`url(#${chart.uid}-regular)`);
      expect(chart['host'].nativeElement.querySelector('.chart')!.getAttribute('aria-label')).toContain('shown in gray');
    });

    it('makes room above the line for band labels', () => {
      init({ points: day });
      expect(chart.y(22)).toBe(26);
    });

    it('greys the dot outside regular hours, and stills it while the market is closed', () => {
      init({ points: day });
      expect(element().querySelector('.dot.live')!.classList).toContain('outside');
      key('ArrowLeft');
      fixture.detectChanges();
      expect(element().querySelector('.dot')!.classList).not.toContain('outside');

      init({ points: [...day.slice(0, 4), { time: 5, value: 22, session: 'CLOSED' }] });
      key('Escape');
      fixture.detectChanges();
      const dot = element().querySelector('.dot.live')!;
      expect(dot.classList).toContain('closed');
      expect(chart.bands.at(-1)!.label).toBeNull();
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
