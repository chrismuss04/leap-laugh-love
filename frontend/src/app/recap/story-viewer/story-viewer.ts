import { Component, Directive, OnDestroy, TemplateRef, computed, contentChildren, effect, inject, input, signal, untracked } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';

/**
 * One card of a story: <ng-template appStoryCard="Top tickers">...</ng-template>. The label names
 * the card for screen readers.
 */
@Directive({ selector: 'ng-template[appStoryCard]' })
export class StoryCardDirective {
  readonly label = input.required<string>({ alias: 'appStoryCard' });
  readonly template = inject(TemplateRef);
}

const reducedMotion = (): boolean =>
  typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

/**
 * Shows cards one at a time, like a story: a progress segment per card, auto-advance, and
 * previous/next/pause by click, keyboard or buttons. Desktop gets a 16:9 card; below 640px of
 * room, a 9:16 one (see story-viewer.css).
 *
 * Only the card on screen is rendered, freshly each time it appears, with the "play" class: card
 * animations, when they come, hang off .card.play and run from the start every time.
 *
 * A timer drives auto-advance; the segment's fill is only its picture, so auto-advance works the
 * same with animations off. With reduced motion it starts paused.
 */
@Component({
  selector: 'app-story-viewer',
  imports: [NgTemplateOutlet],
  templateUrl: './story-viewer.html',
  styleUrl: './story-viewer.css'
})
export class StoryViewerComponent implements OnDestroy {
  /** How long each card shows before moving on, in milliseconds. */
  readonly cardDuration = input(7000);
  /** What the story is called, for screen readers. */
  readonly label = input('Story');

  readonly cards = contentChildren(StoryCardDirective);
  readonly index = signal(0);
  readonly paused = signal(reducedMotion());

  /** The card on screen, in a one-item list tracked by its index, so each visit renders it afresh. */
  readonly showing = computed(() => {
    const card = this.cards()[this.index()];
    return card ? [{ card, index: this.index() }] : [];
  });
  readonly atStart = computed(() => this.index() === 0);
  readonly atEnd = computed(() => this.index() >= this.cards().length - 1);
  readonly stageLabel = computed(() => {
    const card = this.cards()[this.index()];
    return `${this.label()}, card ${this.index() + 1} of ${this.cards().length}${card ? ': ' + card.label() : ''}`;
  });

  private timer: ReturnType<typeof setTimeout> | undefined;
  private deadline = 0;
  private remaining = 0;

  constructor() {
    // A new card, or new cards, start a full countdown; pausing keeps the time left.
    effect(() => {
      this.index();
      this.cards();
      untracked(() => {
        this.remaining = this.cardDuration();
        this.schedule();
      });
    });
  }

  ngOnDestroy(): void {
    clearTimeout(this.timer);
  }

  next(): void {
    if (this.atEnd()) {
      this.setPaused(true);
      return;
    }
    this.index.update(i => i + 1);
  }

  previous(): void {
    // On the first card there's nothing before it; restarting the card would only replay it.
    if (!this.atStart()) {
      this.index.update(i => i - 1);
    }
  }

  togglePause(): void {
    // Play on the last card, after the story ended, starts it again.
    if (this.paused() && this.atEnd() && this.cards().length > 1) {
      this.index.set(0);
    }
    this.setPaused(!this.paused());
  }

  /** The left third goes back, the rest forward. */
  onStageClick(event: MouseEvent): void {
    const stage = event.currentTarget as HTMLElement;
    const box = stage.getBoundingClientRect();
    if (event.clientX - box.left < box.width / 3) {
      this.previous();
    } else {
      this.next();
    }
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowRight') {
      event.preventDefault();
      this.next();
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault();
      this.previous();
    } else if (event.key === ' ') {
      event.preventDefault();
      this.togglePause();
    }
  }

  private setPaused(paused: boolean): void {
    if (paused === this.paused()) {
      return;
    }
    if (paused) {
      this.remaining = Math.max(0, this.deadline - Date.now());
    }
    this.paused.set(paused);
    this.schedule();
  }

  private schedule(): void {
    clearTimeout(this.timer);
    this.timer = undefined;
    if (this.paused() || this.cards().length === 0) {
      return;
    }
    this.deadline = Date.now() + this.remaining;
    this.timer = setTimeout(() => this.next(), this.remaining);
  }
}
