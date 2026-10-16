import { Component } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { StoryCardDirective, StoryViewerComponent } from './story-viewer';

@Component({
  imports: [StoryViewerComponent, StoryCardDirective],
  template: `
    <app-story-viewer label="Test story" [cardDuration]="1000">
      <ng-template appStoryCard="First"><p class="body">one</p></ng-template>
      <ng-template appStoryCard="Second"><p class="body">two</p></ng-template>
      <ng-template appStoryCard="Third"><p class="body">three</p></ng-template>
    </app-story-viewer>`
})
class HostComponent {}

describe('StoryViewerComponent', () => {
  let fixture: ComponentFixture<HostComponent>;

  function mount(reducedMotion = false): void {
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: reducedMotion } as MediaQueryList);
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  }

  afterEach(() => fixture?.destroy());

  const el = (): HTMLElement => fixture.nativeElement;
  const stage = (): HTMLElement => el().querySelector('.stage')!;
  const shown = (): string => el().querySelector('.card .body')?.textContent ?? '';
  const button = (label: string): HTMLButtonElement =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('.controls button'))
      .find(b => b.getAttribute('aria-label') === label || b.textContent?.trim() === label)!;
  const key = (name: string): void => {
    stage().dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true }));
    fixture.detectChanges();
  };
  const clickStageAt = (fraction: number): void => {
    const box = stage().getBoundingClientRect();
    stage().dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: box.left + box.width * fraction }));
    fixture.detectChanges();
  };

  it('shows only the first card, labelled for screen readers, with a segment per card', () => {
    mount();

    expect(el().querySelectorAll('.card')).toHaveLength(1);
    expect(shown()).toBe('one');
    expect(el().querySelector('.card')!.classList).toContain('play');
    expect(el().querySelectorAll('.seg')).toHaveLength(3);
    expect(stage().getAttribute('aria-label')).toBe('Test story, card 1 of 3: First');
    expect(el().querySelector('.counter')!.textContent).toContain('1 / 3');
  });

  it('moves with the buttons, and does nothing going back from the first card', () => {
    mount();
    const first = el().querySelector('.card');

    button('Previous card').click();
    fixture.detectChanges();
    expect(el().querySelector('.card')).toBe(first);
    expect(button('Previous card').disabled).toBe(true);

    button('Next card').click();
    fixture.detectChanges();
    expect(shown()).toBe('two');
    expect(el().querySelectorAll('.seg.done')).toHaveLength(1);

    button('Previous card').click();
    fixture.detectChanges();
    expect(shown()).toBe('one');
  });

  it('goes back from the left third of the card and forward from the rest', () => {
    mount();

    clickStageAt(0.8);
    expect(shown()).toBe('two');
    clickStageAt(0.1);
    expect(shown()).toBe('one');
  });

  it('moves with the arrow keys and pauses with space', () => {
    mount();

    key('ArrowRight');
    expect(shown()).toBe('two');
    key('ArrowLeft');
    expect(shown()).toBe('one');
    key(' ');
    expect(button('Play')).toBeDefined();
    expect(stage().classList).toContain('paused');
  });

  it('renders a card afresh each time it comes back, so its animations replay', () => {
    mount();
    const first = el().querySelector('.card');

    key('ArrowRight');
    key('ArrowLeft');

    expect(el().querySelector('.card')).not.toBe(first);
  });

  it('advances on its own and stops, paused, on the last card', fakeAsync(() => {
    mount();

    tick(1000);
    fixture.detectChanges();
    expect(shown()).toBe('two');
    tick(1000);
    fixture.detectChanges();
    expect(shown()).toBe('three');
    tick(1000);
    fixture.detectChanges();
    expect(shown()).toBe('three');
    expect(button('Play')).toBeDefined();
  }));

  it('keeps the time left on a card across a pause', fakeAsync(() => {
    mount();

    tick(600);
    key(' ');
    tick(5000);
    fixture.detectChanges();
    expect(shown()).toBe('one');

    key(' ');
    tick(399);
    fixture.detectChanges();
    expect(shown()).toBe('one');
    tick(1);
    fixture.detectChanges();
    expect(shown()).toBe('two');
  }));

  it('starts paused with reduced motion, and plays when asked', fakeAsync(() => {
    mount(true);

    tick(5000);
    fixture.detectChanges();
    expect(shown()).toBe('one');

    button('Play').click();
    fixture.detectChanges();
    tick(1000);
    fixture.detectChanges();
    expect(shown()).toBe('two');
    key(' ');
  }));

  it('starts over when played again after the end', fakeAsync(() => {
    mount();
    key('ArrowRight');
    key('ArrowRight');
    key('ArrowRight');      // past the end: pauses
    expect(button('Play')).toBeDefined();

    button('Play').click();
    fixture.detectChanges();

    expect(shown()).toBe('one');
    key(' ');
  }));
});
