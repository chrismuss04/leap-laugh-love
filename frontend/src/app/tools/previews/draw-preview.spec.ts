import { TestBed } from '@angular/core/testing';
import { DrawPreviewComponent } from './draw-preview';

describe('DrawPreviewComponent', () => {
  it('draws today up to now, and a pen line climbing past it', () => {
    const fixture = TestBed.createComponent(DrawPreviewComponent);
    fixture.componentRef.setInput('values', [100, 104, 101, 106]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const now = el.querySelector('.now')!;
    expect(Number(now.getAttribute('cx'))).toBe(78);
    expect(el.querySelector('.history')!.getAttribute('d')).toMatch(/^M6\.0,/);
    const drawn = el.querySelector('.drawn')!.getAttribute('d')!;
    expect(drawn.startsWith('M78,')).toBe(true);
    expect(el.querySelector('animateMotion')!.getAttribute('path')).toBe(drawn);
  });

  it('draws a flat history when there\'s nothing to chart', () => {
    const fixture = TestBed.createComponent(DrawPreviewComponent);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.history')!.getAttribute('d')).toMatch(/^M6\.0,34\.7L78\.0,34\.7$/);
  });
});
