import { ComponentFixture, TestBed } from '@angular/core/testing';
import { OddsRingComponent, formatChance } from './odds-ring';

describe('OddsRingComponent', () => {
  it('formats a chance, never promising 0% or 100% it can\'t round to', () => {
    expect(formatChance(0.342)).toBe('34%');
    expect(formatChance(0.004)).toBe('<1%');
    expect(formatChance(0.996)).toBe('>99%');
    expect(formatChance(0)).toBe('0%');
    expect(formatChance(1)).toBe('100%');
    expect(formatChance(null)).toBe('—');
  });

  it('fills the ring to the chance and says it', () => {
    const fixture: ComponentFixture<OddsRingComponent> = TestBed.createComponent(OddsRingComponent);
    fixture.componentRef.setInput('value', 0.25);
    fixture.componentRef.setInput('label', 'Reaches $110.00 by +2h');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector<SVGElement>('.fill')!.style.strokeDashoffset).toBe('75');
    expect(el.querySelector('.ring')!.getAttribute('aria-label')).toBe('Reaches $110.00 by +2h: 25%');
    expect(el.querySelector('.label')!.textContent).toBe('Reaches $110.00 by +2h');
  });
});
